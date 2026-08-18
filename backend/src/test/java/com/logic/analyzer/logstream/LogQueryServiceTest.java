package com.logic.analyzer.logstream;

import com.logic.analyzer.logstream.dto.LogQueryResult;
import com.logic.analyzer.search.index.LogDocumentBuilder;
import com.logic.analyzer.search.index.SearchIndexService;
import com.logic.analyzer.search.query.LuceneQueryExecutor;
import com.logic.analyzer.search.query.QueryCompiler;
import com.logic.analyzer.source.LogSource;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises LogQueryService against a real (in-memory) Lucene index rather
 * than mocking LogIngestionService - the query path is now Lucene-backed,
 * so seeding real indexed documents is what actually proves filtering,
 * sorting and pagination behave correctly. LogEntry.id is a fresh
 * per-response counter now (same throwaway-identity contract as before,
 * just page-scoped instead of ingestion-scoped), so assertions key off
 * content fields instead of the old fixed ids.
 */
@ExtendWith(MockitoExtension.class)
class LogQueryServiceTest {

    @Mock
    private LogIngestionService ingestionService;
    @Mock
    private SearchIndexService searchIndexService;

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
        writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()));
        searcherManager = new SearcherManager(writer, false, false, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        searcherManager.close();
        writer.close();
        directory.close();
    }

    private LogQueryService service() {
        return new LogQueryService(ingestionService, searchIndexService,
                new LuceneQueryExecutor(searcherManager, facetsConfig), new QueryCompiler(new StandardAnalyzer()));
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
                new LogEntry(1, now.minusSeconds(10), LogLevel.ERROR, "source-a", "app.log", "boom failure"),
                new LogEntry(2, now.minusSeconds(20), LogLevel.INFO, "source-b", "web.log", "all good"),
                new LogEntry(3, now.minusSeconds(30), LogLevel.WARN, "source-a", "app.log", "careful now"),
                new LogEntry(4, now.minus(Duration.ofDays(10)), LogLevel.ERROR, "source-a", "app.log", "ancient failure")
        );
    }

    @Test
    void filtersBySearchLevelSourceAndRange() throws Exception {
        seed(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                "failure", Set.of(LogLevel.ERROR), "source-a", null, 60, "time", "desc", 0, 10));

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().get(0).message()).contains("boom failure");
    }

    @Test
    void excludesEntriesOutsideTheRequestedTimeRange() throws Exception {
        seed(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                null, Set.of(), null, null, 60, "time", "desc", 0, 10));

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.content()).extracting(LogEntry::message).doesNotContain("ancient failure");
    }

    @Test
    void treatsNonPositiveRangeMinutesAsAllTime() throws Exception {
        seed(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                null, Set.of(), null, null, 0, "time", "desc", 0, 10));

        assertThat(result.totalElements()).isEqualTo(4);
        assertThat(result.content()).extracting(LogEntry::message).contains("ancient failure");
    }

    @Test
    void sortsByLevelSeverity() throws Exception {
        seed(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                null, Set.of(), null, null, 60, "level", "asc", 0, 10));

        assertThat(result.content()).extracting(LogEntry::level)
                .containsExactly(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO);
    }

    @Test
    void sortsBySourceThenRespectsDirection() throws Exception {
        seed(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                null, Set.of(), null, null, 60, "source", "asc", 0, 10));

        assertThat(result.content()).extracting(LogEntry::source)
                .containsExactly("source-a", "source-a", "source-b");
    }

    @Test
    void paginatesResults() throws Exception {
        seed(sample());

        LogQueryResult page0 = service().query(new LogQueryParams(null, Set.of(), null, null, 60, "time", "desc", 0, 2));
        LogQueryResult page1 = service().query(new LogQueryParams(null, Set.of(), null, null, 60, "time", "desc", 1, 2));

        assertThat(page0.content()).hasSize(2);
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page1.content()).hasSize(1);
    }

    @Test
    void filtersByFile() throws Exception {
        seed(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                null, Set.of(), null, "web.log", 60, "time", "desc", 0, 10));

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().get(0).message()).isEqualTo("all good");
    }

    @Test
    void searchTermMatchesAgainstFileLabelToo() throws Exception {
        seed(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                "web.log", Set.of(), null, null, 60, "time", "desc", 0, 10));

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().get(0).message()).isEqualTo("all good");
    }

    @Test
    void sortsByFile() throws Exception {
        seed(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                null, Set.of(), null, null, 60, "file", "asc", 0, 10));

        assertThat(result.content()).extracting(LogEntry::file)
                .containsExactly("app.log", "app.log", "web.log");
    }

    @Test
    void listFilesReturnsDistinctSortedNonBlankFileLabels() throws Exception {
        seed(sample());

        List<String> files = service().listFiles(null);

        assertThat(files).containsExactly("app.log", "web.log");
    }

    @Test
    void listFilesScopedToSource() throws Exception {
        seed(sample());

        List<String> files = service().listFiles("source-b");

        assertThat(files).containsExactly("web.log");
    }
}
