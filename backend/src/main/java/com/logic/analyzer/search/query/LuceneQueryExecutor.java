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
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
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
import org.apache.lucene.util.NumericUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    /** Cap on matching docs sampled for avg/min/max/sum/percentile stats - Lucene's facet module counts docs per label, it doesn't sum an arbitrary numeric field, so this walks raw doc values directly and needs its own bound. */
    private static final int MAX_NUMERIC_SAMPLE_DOCS = 50_000;

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
                case AggregationStage.NumericStatsByStage numericStatsBy ->
                        numericStatsBy(searcher, query, numericStatsBy, totalMatched);
                case AggregationStage.NumericStatsOverTimeStage numericStatsOverTime ->
                        numericStatsOverTime(searcher, query, numericStatsOverTime, rangeMinutes, totalMatched);
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
                        .map(lv -> new LogAggregationResult.Bucket(lv.label, lv.value.longValue(), null, null))
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
            buckets.add(new LogAggregationResult.Bucket(range.label, count, rate, null));
        }
        return new LogAggregationResult(null, buckets, totalMatched);
    }

    /**
     * {@code stats avg(field.duration_ms) by source} - Lucene's facet module
     * counts docs per label, it doesn't sum/average an arbitrary numeric
     * field per label, so this walks up to MAX_NUMERIC_SAMPLE_DOCS matching
     * docs directly, reading each one's numeric DocValues (added in
     * LogDocumentBuilder) and its group field's value, then computes the
     * requested statistic per group. Runs a second search independent of the
     * FacetsCollector-based total-count pass above - a modest cost for a
     * simple, correct implementation over trying to force a raw-value scan
     * through machinery built for counting.
     */
    private LogAggregationResult numericStatsBy(IndexSearcher searcher, Query query,
                                                 AggregationStage.NumericStatsByStage stage, long totalMatched) throws IOException {
        String numericField = stage.numericField() + "#numdv";
        String groupField = stage.groupByField();
        Map<String, List<Double>> valuesByGroup = new LinkedHashMap<>();

        TopDocs topDocs = searcher.search(query, MAX_NUMERIC_SAMPLE_DOCS);
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        StoredFields storedFields = searcher.storedFields();

        NumericDocValues numericDV = null;
        SortedDocValues groupDV = null;
        int currentLeaf = -1;

        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            int leafIdx = ReaderUtil.subIndex(scoreDoc.doc, leaves);
            LeafReaderContext leaf = leaves.get(leafIdx);
            int leafDocId = scoreDoc.doc - leaf.docBase;
            if (leafIdx != currentLeaf) {
                numericDV = leaf.reader().getNumericDocValues(numericField);
                // Only fixed fields (source/file/level) carry a plain SortedDocValuesField;
                // dynamic field.<name> targets fall back to the stored field below.
                groupDV = groupField == null ? null : leaf.reader().getSortedDocValues(groupField);
                currentLeaf = leafIdx;
            }
            if (numericDV == null || !numericDV.advanceExact(leafDocId)) {
                continue;
            }
            double value = NumericUtils.sortableLongToDouble(numericDV.longValue());

            String groupKey;
            if (groupField == null) {
                // No "by <field>" clause - a single aggregate across every matched doc.
                groupKey = "all";
            } else if (groupDV != null && groupDV.advanceExact(leafDocId)) {
                groupKey = groupDV.lookupOrd(groupDV.ordValue()).utf8ToString();
            } else {
                groupKey = storedFields.document(scoreDoc.doc).get(groupField);
            }
            if (groupKey == null) {
                continue;
            }
            valuesByGroup.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(value);
        }

        List<LogAggregationResult.Bucket> buckets = valuesByGroup.entrySet().stream()
                .map(e -> new LogAggregationResult.Bucket(e.getKey(), e.getValue().size(), null, computeStat(e.getValue(), stage.function())))
                .sorted(Comparator.comparing(LogAggregationResult.Bucket::key))
                .limit(MAX_GROUP_BUCKETS)
                .toList();
        return new LogAggregationResult(groupField, buckets, totalMatched);
    }

    /** {@code avg_over_time(field.duration_ms{...}[5m])} - same raw-doc-value walk as numericStatsBy, bucketed by time instead of a group field. */
    private LogAggregationResult numericStatsOverTime(IndexSearcher searcher, Query query,
            AggregationStage.NumericStatsOverTimeStage stage, long rangeMinutes, long totalMatched) throws IOException {
        long bucketMillis = Math.max(1, stage.bucket().toMillis());
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

        String numericField = stage.numericField() + "#numdv";
        Map<Integer, List<Double>> valuesByBucketIndex = new HashMap<>();

        TopDocs topDocs = searcher.search(query, MAX_NUMERIC_SAMPLE_DOCS);
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();

        NumericDocValues numericDV = null;
        NumericDocValues timestampDV = null;
        int currentLeaf = -1;

        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            int leafIdx = ReaderUtil.subIndex(scoreDoc.doc, leaves);
            LeafReaderContext leaf = leaves.get(leafIdx);
            int leafDocId = scoreDoc.doc - leaf.docBase;
            if (leafIdx != currentLeaf) {
                numericDV = leaf.reader().getNumericDocValues(numericField);
                timestampDV = leaf.reader().getNumericDocValues("timestampMillis");
                currentLeaf = leafIdx;
            }
            if (numericDV == null || !numericDV.advanceExact(leafDocId)
                    || timestampDV == null || !timestampDV.advanceExact(leafDocId)) {
                continue;
            }
            double value = NumericUtils.sortableLongToDouble(numericDV.longValue());
            long ts = timestampDV.longValue();
            int bucketIndex = (int) Math.max(0, Math.min(ranges.size() - 1, (ts - windowStart) / bucketMillis));
            valuesByBucketIndex.computeIfAbsent(bucketIndex, k -> new ArrayList<>()).add(value);
        }

        List<LogAggregationResult.Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            List<Double> values = valuesByBucketIndex.getOrDefault(i, List.of());
            Double stat = values.isEmpty() ? null : computeStat(values, stage.function());
            buckets.add(new LogAggregationResult.Bucket(ranges.get(i).label, values.size(), null, stat));
        }
        return new LogAggregationResult(null, buckets, totalMatched);
    }

    private double computeStat(List<Double> values, AggregationStage.NumericStatFunction function) {
        return switch (function) {
            case AVG -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            case MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            case MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case SUM -> values.stream().mapToDouble(Double::doubleValue).sum();
            case P50 -> percentile(values, 50);
            case P95 -> percentile(values, 95);
            case P99 -> percentile(values, 99);
        };
    }

    /** Nearest-rank percentile - simple and exact for the bounded (MAX_NUMERIC_SAMPLE_DOCS) sample sizes this app deals with, no approximation needed. */
    private double percentile(List<Double> values, int pct) {
        List<Double> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /**
     * Distinct, sorted file labels among documents matching the source scope
     * (or every document if source is blank). {@code excludedSources} keeps a
     * disabled source's files out of the filter dropdown, same as its entries
     * are kept out of query results.
     */
    public List<String> listFiles(String source, List<String> excludedSources) {
        Query base = source == null || source.isBlank()
                ? new MatchAllDocsQuery()
                : new TermQuery(new Term("source", source));
        Query query = excludedSources.isEmpty() ? base : withExclusions(base, excludedSources);

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

    private Query withExclusions(Query base, List<String> excludedSources) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder().add(base, BooleanClause.Occur.MUST);
        for (String excluded : excludedSources) {
            builder.add(new TermQuery(new Term("source", excluded)), BooleanClause.Occur.MUST_NOT);
        }
        return builder.build();
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
