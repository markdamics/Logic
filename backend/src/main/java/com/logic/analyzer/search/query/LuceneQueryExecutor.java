package com.logic.analyzer.search.query;

import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.logstream.dto.LogAggregationResult;
import com.logic.analyzer.logstream.dto.LogQueryResult;
import org.apache.lucene.document.Document;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsCollectorManager;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.range.LongRange;
import org.apache.lucene.facet.range.LongRangeFacetCounts;
import org.apache.lucene.facet.sortedset.DefaultSortedSetDocValuesReaderState;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetCounts;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesReaderState;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Runs a compiled Lucene {@link Query} and shapes the result as a
 * {@link LogQueryResult} - the one execution path shared by every query
 * entry point (the simple-filter UI, the query-bar, and eventually saved
 * searches), so indexed search/sort/pagination behaves identically no
 * matter how the Query was built.
 */
@Component
public class LuceneQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(LuceneQueryExecutor.class);

    /** Cap on distinct groups returned by "stats count by <field>" - a UI bar chart, not an unbounded export. */
    private static final int MAX_GROUP_BUCKETS = 100;
    /** Cap on time buckets for count_over_time/rate, so a tiny bucket duration over a huge window can't blow up the response. */
    private static final int MAX_TIME_BUCKETS = 500;
    /** count_over_time/rate need a bounded window; "all time" (rangeMinutes <= 0) falls back to this many minutes. */
    private static final long DEFAULT_AGGREGATION_WINDOW_MINUTES = 24 * 60;

    private final SearcherManager searcherManager;
    private final FacetsConfig facetsConfig;

    public LuceneQueryExecutor(SearcherManager searcherManager, FacetsConfig facetsConfig) {
        this.searcherManager = searcherManager;
        this.facetsConfig = facetsConfig;
    }

    public LogQueryResult execute(Query query, String sortBy, String sortDir, int page, int size) {
        IndexSearcher searcher = acquire();
        try {
            int pageSize = Math.max(1, size);
            int totalElements = searcher.count(query);
            int totalPages = (int) Math.ceil(totalElements / (double) pageSize);
            int fromIndex = Math.min(Math.max(page, 0) * pageSize, totalElements);
            int toIndex = Math.min(fromIndex + pageSize, totalElements);

            List<LogEntry> content = new ArrayList<>();
            if (toIndex > fromIndex) {
                Sort sort = sortFor(sortBy, sortDir);
                TopFieldDocs topDocs = searcher.search(query, toIndex, sort);
                StoredFields storedFields = searcher.storedFields();
                long id = 1;
                for (int i = fromIndex; i < toIndex; i++) {
                    Document doc = storedFields.document(topDocs.scoreDocs[i].doc);
                    content.add(toLogEntry(id++, doc));
                }
            }

            log.debug("Search matched {} entries (page {} of {}, {} returned)",
                    totalElements, page, totalPages, content.size());
            return new LogQueryResult(content, page, pageSize, totalElements, totalPages, null);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to execute search", e);
        } finally {
            releaseQuietly(searcher);
        }
    }

    /**
     * Runs a query that ends in an aggregation stage: content is always
     * empty, and {@link LogQueryResult#aggregation()} carries the grouped
     * counts instead. Uses Lucene's facet module (SortedSetDocValuesFacetCounts
     * for "stats count by", LongRangeFacetCounts for time-bucketed counts)
     * rather than materializing and counting matched docs in Java - the
     * durable index can hold far more than a single tail-window's worth of
     * entries once retention is in play, so this needs to scale past what a
     * naive in-memory group-by would.
     */
    public LogQueryResult executeAggregation(Query query, AggregationStage stage, long rangeMinutes) {
        IndexSearcher searcher = acquire();
        try {
            FacetsCollectorManager.FacetsResult searchResult =
                    FacetsCollectorManager.search(searcher, query, 0, new FacetsCollectorManager());
            long totalMatched = searchResult.topDocs().totalHits.value();
            FacetsCollector facetsCollector = searchResult.facetsCollector();

            LogAggregationResult aggregation = switch (stage) {
                case AggregationStage.StatsCountByStage statsCountBy ->
                        statsCountBy(searcher, facetsCollector, statsCountBy, totalMatched);
                case AggregationStage.CountOverTimeStage countOverTime ->
                        bucketedCount(facetsCollector, countOverTime.bucket(), rangeMinutes, false, totalMatched);
                case AggregationStage.RateStage rate ->
                        bucketedCount(facetsCollector, rate.bucket(), rangeMinutes, true, totalMatched);
            };

            log.debug("Aggregation matched {} entries, {} buckets", totalMatched, aggregation.buckets().size());
            return new LogQueryResult(List.of(), 0, 0, (int) Math.min(totalMatched, Integer.MAX_VALUE), 1, aggregation);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to execute aggregation", e);
        } finally {
            releaseQuietly(searcher);
        }
    }

    private LogAggregationResult statsCountBy(IndexSearcher searcher, FacetsCollector facetsCollector,
                                               AggregationStage.StatsCountByStage stage, long totalMatched) throws IOException {
        String field = stage.groupByField();
        SortedSetDocValuesReaderState state =
                new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader(), facetsConfig);
        Facets facets = new SortedSetDocValuesFacetCounts(state, facetsCollector);
        FacetResult result = facets.getTopChildren(MAX_GROUP_BUCKETS, field);

        List<LogAggregationResult.Bucket> buckets = result == null
                ? List.of()
                : List.of(result.labelValues).stream()
                        .map(lv -> new LogAggregationResult.Bucket(lv.label, lv.value.longValue(), null))
                        .toList();
        return new LogAggregationResult(field, buckets, totalMatched);
    }

    private LogAggregationResult bucketedCount(FacetsCollector facetsCollector, Duration bucket, long rangeMinutes,
                                                boolean asRate, long totalMatched) throws IOException {
        long bucketMillis = Math.max(1, bucket.toMillis());
        long now = System.currentTimeMillis();
        long effectiveRangeMinutes = rangeMinutes > 0 ? rangeMinutes : DEFAULT_AGGREGATION_WINDOW_MINUTES;
        long windowStart = now - Duration.ofMinutes(effectiveRangeMinutes).toMillis();

        List<LongRange> ranges = new ArrayList<>();
        long cursor = windowStart;
        while (cursor < now && ranges.size() < MAX_TIME_BUCKETS) {
            long end = Math.min(cursor + bucketMillis, now);
            ranges.add(new LongRange(Instant.ofEpochMilli(cursor).toString(), cursor, true, end, false));
            cursor = end;
        }
        if (ranges.isEmpty()) {
            return new LogAggregationResult(null, List.of(), totalMatched);
        }

        Facets facets = new LongRangeFacetCounts("timestampMillis", facetsCollector, ranges.toArray(new LongRange[0]));
        FacetResult result = facets.getTopChildren(ranges.size(), "timestampMillis");

        // getTopChildren's ordering isn't guaranteed to be chronological, so
        // look counts up by the range's own label rather than trusting
        // result order - the response must read left-to-right as a time series.
        Map<String, Long> countsByLabel = new HashMap<>();
        if (result != null) {
            for (LabelAndValue lv : result.labelValues) {
                countsByLabel.put(lv.label, lv.value.longValue());
            }
        }

        double bucketSeconds = bucketMillis / 1000.0;
        List<LogAggregationResult.Bucket> buckets = new ArrayList<>();
        for (LongRange range : ranges) {
            long count = countsByLabel.getOrDefault(range.label, 0L);
            Double rate = asRate ? count / bucketSeconds : null;
            buckets.add(new LogAggregationResult.Bucket(range.label, count, rate));
        }
        return new LogAggregationResult(null, buckets, totalMatched);
    }

    /** Distinct, sorted file labels among documents matching the source scope (or every document if source is blank). */
    public List<String> listFiles(String source) {
        Query query = source == null || source.isBlank()
                ? new MatchAllDocsQuery()
                : new TermQuery(new Term("source", source));

        IndexSearcher searcher = acquire();
        try {
            int total = searcher.count(query);
            if (total == 0) {
                return List.of();
            }
            TopDocs topDocs = searcher.search(query, total);
            StoredFields storedFields = searcher.storedFields();
            TreeSet<String> files = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                String file = storedFields.document(scoreDoc.doc).get("file");
                if (file != null && !file.isBlank()) {
                    files.add(file);
                }
            }
            return List.copyOf(files);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list indexed files", e);
        } finally {
            releaseQuietly(searcher);
        }
    }

    private IndexSearcher acquire() {
        try {
            searcherManager.maybeRefresh();
            return searcherManager.acquire();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to acquire index searcher", e);
        }
    }

    private void releaseQuietly(IndexSearcher searcher) {
        try {
            searcherManager.release(searcher);
        } catch (IOException e) {
            log.warn("Failed to release index searcher: {}", e.getMessage());
        }
    }

    private Sort sortFor(String sortBy, String sortDir) {
        boolean reverse = "desc".equalsIgnoreCase(sortDir);
        SortField field = switch (sortBy == null ? "time" : sortBy) {
            case "level" -> new SortField("levelOrdinal", SortField.Type.INT, reverse);
            case "source" -> new SortField("source", SortField.Type.STRING, reverse);
            case "file" -> new SortField("file", SortField.Type.STRING, reverse);
            default -> new SortField("timestampMillis", SortField.Type.LONG, reverse);
        };
        return new Sort(field);
    }

    private LogEntry toLogEntry(long id, Document doc) {
        String file = doc.get("file");
        return new LogEntry(
                id,
                Instant.ofEpochMilli(Long.parseLong(doc.get("timestampMillis"))),
                LogLevel.valueOf(doc.get("level")),
                doc.get("source"),
                file == null || file.isEmpty() ? null : file,
                doc.get("message")
        );
    }
}
