package com.logic.analyzer.search;

import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.logstream.dto.LogAggregationResult;
import com.logic.analyzer.logstream.dto.LogQueryResult;
import com.logic.analyzer.search.index.LogDocumentBuilder;
import com.logic.analyzer.search.query.LogQlSubsetQueryParser;
import com.logic.analyzer.search.query.LuceneQueryExecutor;
import com.logic.analyzer.search.query.LuceneSyntaxQueryParser;
import com.logic.analyzer.search.query.QueryCompiler;
import com.logic.analyzer.search.query.SplSubsetQueryParser;
import com.logic.analyzer.search.query.QueryLanguage;
import com.logic.analyzer.search.query.QueryParser;
import com.logic.analyzer.source.LogSource;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the full query-bar pipeline against a real (in-memory) Lucene
 * index: structured field extraction at index time (LogDocumentBuilder +
 * MessageFieldExtractor), Lucene-syntax parsing, and execution - proving
 * field.&lt;name&gt; queries and boolean/NOT syntax work end to end, and that
 * plain unstructured messages keep indexing/searching correctly alongside them.
 */
@ExtendWith(MockitoExtension.class)
class SearchQueryServiceTest {

    private final Analyzer analyzer = new PerFieldAnalyzerWrapper(new KeywordAnalyzer(),
            Map.of("message", new StandardAnalyzer(), "_all", new StandardAnalyzer()));
    private final FacetsConfig facetsConfig = new FacetsConfig();
    private final LogDocumentBuilder documentBuilder = new LogDocumentBuilder(facetsConfig);
    private final LogSource testSource = mock(LogSource.class);

    private Directory directory;
    private IndexWriter writer;
    private SearcherManager searcherManager;

    @BeforeEach
    void setUp() throws Exception {
        when(testSource.getId()).thenReturn(1L);
        directory = new ByteBuffersDirectory();
        writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
        searcherManager = new SearcherManager(writer, false, false, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        searcherManager.close();
        writer.close();
        directory.close();
    }

    private SearchQueryService service() {
        List<QueryParser> parsers = List.of(
                new LuceneSyntaxQueryParser(analyzer), new SplSubsetQueryParser(), new LogQlSubsetQueryParser());
        return new SearchQueryService(parsers, new QueryCompiler(analyzer),
                new LuceneQueryExecutor(searcherManager, facetsConfig));
    }

    private void seed(List<LogEntry> entries) throws Exception {
        for (LogEntry entry : entries) {
            String docId = "doc-" + entry.id();
            Document doc = documentBuilder.build(testSource, entry, docId);
            writer.updateDocument(new Term("docId", docId), doc);
        }
        writer.commit();
        searcherManager.maybeRefresh();
    }

    private List<LogEntry> sample() {
        Instant now = Instant.now();
        return List.of(
                new LogEntry(1, now.minusSeconds(10), LogLevel.ERROR, "payments-api",
                        "app.log", "{\"status\": 500, \"service\": \"payments\"}"),
                new LogEntry(2, now.minusSeconds(20), LogLevel.ERROR, "test-runner",
                        "app.log", "{\"status\": 500, \"service\": \"test\"}"),
                new LogEntry(3, now.minusSeconds(30), LogLevel.INFO, "payments-api",
                        "app.log", "{\"status\": 200, \"service\": \"payments\"}"),
                new LogEntry(4, now.minusSeconds(40), LogLevel.WARN, "payments-api",
                        "app.log", "plain unstructured message with no shape")
        );
    }

    @Test
    void matchesOnADynamicStructuredField() throws Exception {
        seed(sample());

        LogQueryResult result = service().query("field.status:500", QueryLanguage.LUCENE,
                null, null, 0, "time", "desc", 0, 10);

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.content()).extracting(LogEntry::source)
                .containsExactlyInAnyOrder("payments-api", "test-runner");
    }

    @Test
    void combinesBooleanAndNotAcrossFixedFields() throws Exception {
        seed(sample());

        LogQueryResult result = service().query("level:ERROR AND NOT source:test-runner", QueryLanguage.LUCENE,
                null, null, 0, "time", "desc", 0, 10);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().get(0).source()).isEqualTo("payments-api");
        assertThat(result.content().get(0).level()).isEqualTo(LogLevel.ERROR);
    }

    @Test
    void leavesUnstructuredMessagesFullTextSearchable() throws Exception {
        seed(sample());

        LogQueryResult result = service().query("unstructured", QueryLanguage.LUCENE,
                null, null, 0, "time", "desc", 0, 10);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().get(0).id()).isNotNull();
    }

    @Test
    void appliesSourceAndFileScopeAlongsideTheQuery() throws Exception {
        seed(sample());

        LogQueryResult result = service().query("field.status:500", QueryLanguage.LUCENE,
                "payments-api", null, 0, "time", "desc", 0, 10);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().get(0).source()).isEqualTo("payments-api");
    }

    @Test
    void rejectsInvalidLuceneSyntax() {
        assertThatThrownBy(() -> service().query("level:ERROR AND (", QueryLanguage.LUCENE,
                null, null, 0, "time", "desc", 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splBooleanAndComparisonAcrossFixedAndDynamicFields() throws Exception {
        seed(sample());

        LogQueryResult result = service().query("level=ERROR AND source=payments-api", QueryLanguage.SPL,
                null, null, 0, "time", "desc", 0, 10);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().get(0).source()).isEqualTo("payments-api");

        LogQueryResult numeric = service().query("status>=500", QueryLanguage.SPL,
                null, null, 0, "time", "desc", 0, 10);
        assertThat(numeric.totalElements()).isEqualTo(2);
    }

    @Test
    void splNotAndPhraseSearch() throws Exception {
        seed(sample());

        LogQueryResult result = service().query("\"unstructured message\" NOT source=test-runner", QueryLanguage.SPL,
                null, null, 0, "time", "desc", 0, 10);

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().get(0).message()).contains("unstructured message");
    }

    @Test
    void splStatsCountByProducesAnAggregationInsteadOfRows() throws Exception {
        seed(sample());

        LogQueryResult result = service().query("level=ERROR | stats count by source", QueryLanguage.SPL,
                null, null, 0, "time", "desc", 0, 10);

        assertThat(result.content()).isEmpty();
        assertThat(result.aggregation()).isNotNull();
        assertThat(result.aggregation().totalMatched()).isEqualTo(2);
        assertThat(result.aggregation().buckets())
                .extracting(LogAggregationResult.Bucket::key, LogAggregationResult.Bucket::count)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("payments-api", 1L),
                        org.assertj.core.groups.Tuple.tuple("test-runner", 1L));
    }

    @Test
    void logQlSelectorAndLineFilter() throws Exception {
        seed(sample());

        LogQueryResult selectorOnly = service().query("{source=\"payments-api\", level=\"ERROR\"}", QueryLanguage.LOGQL,
                null, null, 0, "time", "desc", 0, 10);
        assertThat(selectorOnly.totalElements()).isEqualTo(1);

        LogQueryResult withFilter = service().query("{level=\"WARN\"} |= \"unstructured\"", QueryLanguage.LOGQL,
                null, null, 0, "time", "desc", 0, 10);
        assertThat(withFilter.totalElements()).isEqualTo(1);
        assertThat(withFilter.content().get(0).source()).isEqualTo("payments-api");
    }

    @Test
    void logQlCountOverTimeBucketsChronologicallyAndSumsToTotal() throws Exception {
        seed(sample());

        LogQueryResult result = service().query("count_over_time({}[1h])", QueryLanguage.LOGQL,
                null, null, 60, "time", "desc", 0, 10);

        assertThat(result.content()).isEmpty();
        assertThat(result.aggregation().totalMatched()).isEqualTo(4);
        long summedCounts = result.aggregation().buckets().stream()
                .mapToLong(LogAggregationResult.Bucket::count).sum();
        assertThat(summedCounts).isEqualTo(4);
        assertThat(result.aggregation().buckets()).allMatch(b -> b.rate() == null);
    }

    @Test
    void logQlRateDividesCountByBucketSeconds() throws Exception {
        seed(sample());

        LogQueryResult result = service().query("rate({level=\"ERROR\"}[60s])", QueryLanguage.LOGQL,
                null, null, 60, "time", "desc", 0, 10);

        assertThat(result.aggregation().totalMatched()).isEqualTo(2);
        assertThat(result.aggregation().buckets()).allSatisfy(b -> {
            if (b.count() > 0) {
                assertThat(b.rate()).isEqualTo(b.count() / 60.0);
            }
        });
    }

    private List<LogEntry> numericSample() {
        Instant now = Instant.now();
        return List.of(
                new LogEntry(11, now.minusSeconds(10), LogLevel.INFO, "payments-api",
                        "app.log", "{\"duration_ms\": 100}"),
                new LogEntry(12, now.minusSeconds(20), LogLevel.INFO, "payments-api",
                        "app.log", "{\"duration_ms\": 200}"),
                new LogEntry(13, now.minusSeconds(30), LogLevel.INFO, "payments-api",
                        "app.log", "{\"duration_ms\": 300}"),
                new LogEntry(14, now.minusSeconds(40), LogLevel.INFO, "test-runner",
                        "app.log", "{\"duration_ms\": 50}")
        );
    }

    @Test
    void splNumericStatsByGroupsAndComputesTheAveragePerGroup() throws Exception {
        seed(numericSample());

        LogQueryResult result = service().query("| stats avg(field.duration_ms) by source", QueryLanguage.SPL,
                null, null, 0, "time", "desc", 0, 10);

        assertThat(result.content()).isEmpty();
        assertThat(result.aggregation().totalMatched()).isEqualTo(4);
        assertThat(result.aggregation().buckets())
                .extracting(LogAggregationResult.Bucket::key, LogAggregationResult.Bucket::statValue)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("payments-api", 200.0),
                        org.assertj.core.groups.Tuple.tuple("test-runner", 50.0));
    }

    @Test
    void splNumericStatsWithoutByComputesASingleAggregateAcrossAllMatches() throws Exception {
        seed(numericSample());

        LogQueryResult min = service().query("| stats min(field.duration_ms)", QueryLanguage.SPL,
                null, null, 0, "time", "desc", 0, 10);
        assertThat(min.aggregation().buckets()).hasSize(1);
        assertThat(min.aggregation().buckets().get(0).statValue()).isEqualTo(50.0);

        LogQueryResult max = service().query("| stats max(field.duration_ms)", QueryLanguage.SPL,
                null, null, 0, "time", "desc", 0, 10);
        assertThat(max.aggregation().buckets().get(0).statValue()).isEqualTo(300.0);

        LogQueryResult sum = service().query("| stats sum(field.duration_ms)", QueryLanguage.SPL,
                null, null, 0, "time", "desc", 0, 10);
        assertThat(sum.aggregation().buckets().get(0).statValue()).isEqualTo(650.0);

        LogQueryResult p95 = service().query("| stats p95(field.duration_ms)", QueryLanguage.SPL,
                null, null, 0, "time", "desc", 0, 10);
        assertThat(p95.aggregation().buckets().get(0).statValue()).isEqualTo(300.0);
    }

    @Test
    void logQlAvgOverTimeComputesTheAverageWithinEachTimeBucket() throws Exception {
        seed(numericSample());

        LogQueryResult result = service().query("avg_over_time(field.duration_ms{}[1h])", QueryLanguage.LOGQL,
                null, null, 60, "time", "desc", 0, 10);

        assertThat(result.content()).isEmpty();
        assertThat(result.aggregation().totalMatched()).isEqualTo(4);
        // all 4 docs fall within a few seconds of "now" so a 1h-wide bucket over a 60-minute window puts them all together.
        assertThat(result.aggregation().buckets())
                .filteredOn(b -> b.statValue() != null)
                .hasSize(1)
                .allSatisfy(b -> assertThat(b.statValue()).isEqualTo(162.5));
    }
}
