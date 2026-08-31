package com.logic.analyzer.search.index;

import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.logstream.LogIngestionService;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.logstream.ingest.TailSource;
import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.LogSourceRepository;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the "live source keeps re-adding old logs" bug: a
 * reindex pass used to only ever upsert-by-Term, so a line that scrolled out
 * of a file's tail window (or a file that got rewritten/rotated rather than
 * purely appended to) left its old document behind forever, growing the
 * index far past the file's actual content. Each (source, file) pass must
 * now reflect the file's current read exactly.
 */
@ExtendWith(MockitoExtension.class)
class SearchIndexServiceTest {

    @Mock
    private LogSourceRepository sourceRepository;
    @Mock
    private LogIngestionService ingestionService;

    private final FacetsConfig facetsConfig = new FacetsConfig();
    private final LogDocumentBuilder documentBuilder = new LogDocumentBuilder(facetsConfig);
    private final LogSource testSource = mock(LogSource.class);

    private Directory directory;
    private IndexWriter writer;
    private SearcherManager searcherManager;
    private SearchIndexService service;

    @BeforeEach
    void setUp() throws Exception {
        when(testSource.getId()).thenReturn(1L);
        when(testSource.getName()).thenReturn("events");
        when(testSource.isEnabled()).thenReturn(true);
        directory = new ByteBuffersDirectory();
        writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()));
        searcherManager = new SearcherManager(writer, false, false, null);
        when(sourceRepository.findAll()).thenReturn(List.of(testSource));
        service = new SearchIndexService(sourceRepository, ingestionService, writer, searcherManager, documentBuilder);
    }

    @AfterEach
    void tearDown() throws Exception {
        searcherManager.close();
        writer.close();
        directory.close();
    }

    private int totalIndexedDocs() throws Exception {
        searcherManager.maybeRefresh();
        IndexSearcher searcher = searcherManager.acquire();
        try {
            return searcher.count(new MatchAllDocsQuery());
        } finally {
            searcherManager.release(searcher);
        }
    }

    private LogIngestionService.IndexableSourceRead readOf(long fingerprintSize, LogEntry... entries) {
        return new LogIngestionService.IndexableSourceRead(
                List.of(entries),
                Map.of("app.log", new TailSource.Fingerprint(fingerprintSize, Instant.now())));
    }

    @Test
    void reindexingAFileWithFewerLinesDropsTheStaleDocuments() throws Exception {
        Instant now = Instant.now();
        when(ingestionService.readForIndexing(testSource)).thenReturn(readOf(1000,
                new LogEntry(1, now, LogLevel.INFO, "events", "app.log", "line one"),
                new LogEntry(2, now, LogLevel.INFO, "events", "app.log", "line two"),
                new LogEntry(3, now, LogLevel.INFO, "events", "app.log", "line three")));

        service.reindexAll();

        assertThat(totalIndexedDocs()).isEqualTo(3);

        // The file's tail window shrank (rewritten/rotated, not purely appended to) - a
        // different fingerprint so the pass isn't skipped, and only one line survives.
        when(ingestionService.readForIndexing(testSource)).thenReturn(readOf(10,
                new LogEntry(1, now, LogLevel.INFO, "events", "app.log", "only line now")));

        service.reindexAll();

        assertThat(totalIndexedDocs()).isEqualTo(1);
    }

    @Test
    void reappendingTheSameLinesPlusOneNewLineOnlyAddsOne() throws Exception {
        Instant now = Instant.now();
        when(ingestionService.readForIndexing(testSource)).thenReturn(readOf(500,
                new LogEntry(1, now, LogLevel.INFO, "events", "app.log", "line one"),
                new LogEntry(2, now, LogLevel.INFO, "events", "app.log", "line two")));

        service.reindexAll();
        assertThat(totalIndexedDocs()).isEqualTo(2);

        when(ingestionService.readForIndexing(testSource)).thenReturn(readOf(600,
                new LogEntry(1, now, LogLevel.INFO, "events", "app.log", "line one"),
                new LogEntry(2, now, LogLevel.INFO, "events", "app.log", "line two"),
                new LogEntry(3, now, LogLevel.INFO, "events", "app.log", "line three")));

        service.reindexAll();

        assertThat(totalIndexedDocs()).isEqualTo(3);
    }

    @Test
    void reindexingAnUnchangedFileDoesNotAccumulateDuplicates() throws Exception {
        Instant now = Instant.now();
        when(ingestionService.readForIndexing(testSource)).thenReturn(readOf(500,
                new LogEntry(1, now, LogLevel.INFO, "events", "app.log", "steady line")));

        service.reindexAll();
        service.reindexAll();
        service.reindexAll();

        assertThat(totalIndexedDocs()).isEqualTo(1);
    }

    @Test
    void aFileEmptiedOutCompletelyStillHasItsStaleDocumentsPurged() throws Exception {
        // Every line in the file got deleted - readForIndexing() now returns zero entries
        // for it, even though the file still exists on disk (still has a fingerprint).
        // entriesByFile grouping alone would never visit this file again to purge it.
        Instant now = Instant.now();
        when(ingestionService.readForIndexing(testSource)).thenReturn(readOf(500,
                new LogEntry(1, now, LogLevel.INFO, "events", "app.log", "line one"),
                new LogEntry(2, now, LogLevel.INFO, "events", "app.log", "line two")));

        service.reindexAll();
        assertThat(totalIndexedDocs()).isEqualTo(2);

        when(ingestionService.readForIndexing(testSource)).thenReturn(
                new LogIngestionService.IndexableSourceRead(
                        List.of(), Map.of("app.log", new TailSource.Fingerprint(0, Instant.now()))));

        service.reindexAll();

        assertThat(totalIndexedDocs()).isEqualTo(0);
    }

    @Test
    void aFileDeletedEntirelyFromADirectorySourceStillHasItsStaleDocumentsPurged() throws Exception {
        // The file is gone from disk entirely (removed from a LOCAL_DIRECTORY source with
        // other files still present) - it no longer appears in fingerprintsByFile either,
        // so only the "previously indexed" tracking can catch this one.
        Instant now = Instant.now();
        when(ingestionService.readForIndexing(testSource)).thenReturn(new LogIngestionService.IndexableSourceRead(
                List.of(
                        new LogEntry(1, now, LogLevel.INFO, "events", "gone.log", "will be deleted"),
                        new LogEntry(2, now, LogLevel.INFO, "events", "stays.log", "sticks around")),
                Map.of(
                        "gone.log", new TailSource.Fingerprint(100, now),
                        "stays.log", new TailSource.Fingerprint(200, now))));

        service.reindexAll();
        assertThat(totalIndexedDocs()).isEqualTo(2);

        when(ingestionService.readForIndexing(testSource)).thenReturn(new LogIngestionService.IndexableSourceRead(
                List.of(new LogEntry(2, now, LogLevel.INFO, "events", "stays.log", "sticks around")),
                Map.of("stays.log", new TailSource.Fingerprint(200, now))));

        service.reindexAll();

        assertThat(totalIndexedDocs()).isEqualTo(1);
    }
}
