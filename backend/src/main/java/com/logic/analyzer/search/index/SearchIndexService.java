package com.logic.analyzer.search.index;

import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.logstream.LogIngestionService;
import com.logic.analyzer.logstream.ingest.TailSource;
import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.LogSourceRepository;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.SearcherManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Keeps the Lucene search index up to date in the background, decoupled from
 * the interactive Log Stream cache in {@link LogIngestionService} - it runs
 * on its own schedule regardless of whether a source is "live" (which only
 * governs that other, UI-facing cache's TTL), so the durable index can get
 * ahead of a stale non-live snapshot rather than being bound by it.
 *
 * Writes are idempotent (Lucene's updateDocument upsert keyed by a stable,
 * content-derived docId), so an in-memory per-file fingerprint is only a
 * cheap gate to skip re-submitting unchanged files - losing it on restart
 * costs one redundant (bounded, cheap) reindex pass, not a correctness bug.
 */
@Service
public class SearchIndexService {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexService.class);

    private final LogSourceRepository sourceRepository;
    private final LogIngestionService ingestionService;
    private final IndexWriter indexWriter;
    private final SearcherManager searcherManager;
    private final LogDocumentBuilder documentBuilder;
    private final Map<String, TailSource.Fingerprint> lastIndexedFingerprint = new ConcurrentHashMap<>();

    public SearchIndexService(LogSourceRepository sourceRepository, LogIngestionService ingestionService,
                               IndexWriter indexWriter, SearcherManager searcherManager, LogDocumentBuilder documentBuilder) {
        this.sourceRepository = sourceRepository;
        this.ingestionService = ingestionService;
        this.indexWriter = indexWriter;
        this.searcherManager = searcherManager;
        this.documentBuilder = documentBuilder;
    }

    @Scheduled(fixedDelayString = "${app.search.index-interval-ms:5000}")
    public void reindexAll() {
        int indexedFiles = 0;
        for (LogSource source : sourceRepository.findAll()) {
            if (!source.isEnabled()) {
                continue;
            }
            indexedFiles += indexSource(source);
        }
        try {
            if (indexedFiles > 0) {
                indexWriter.commit();
            }
            searcherManager.maybeRefresh();
        } catch (IOException e) {
            log.warn("Failed to commit/refresh search index: {}", e.getMessage());
        }
    }

    /** Forces an immediate, full reindex bypassing the fingerprint gate - used by the Reload action. */
    public void reindexNow() {
        lastIndexedFingerprint.clear();
        reindexAll();
        log.info("Search index reindex forced by reload");
    }

    private int indexSource(LogSource source) {
        LogIngestionService.IndexableSourceRead read;
        try {
            read = ingestionService.readForIndexing(source);
        } catch (Exception e) {
            log.warn("Failed to read source {} ('{}') for indexing: {}", source.getId(), source.getName(), e.getMessage());
            return 0;
        }

        Map<String, List<LogEntry>> entriesByFile = read.entries().stream()
                .collect(Collectors.groupingBy(e -> e.file() == null ? "" : e.file(), LinkedHashMap::new, Collectors.toList()));

        int indexed = 0;
        for (Map.Entry<String, List<LogEntry>> fileEntries : entriesByFile.entrySet()) {
            String fingerprintKey = source.getId() + ":" + fileEntries.getKey();
            TailSource.Fingerprint currentFingerprint = read.fingerprintsByFile().get(fileEntries.getKey());
            if (currentFingerprint != null && currentFingerprint.equals(lastIndexedFingerprint.get(fingerprintKey))) {
                continue; // unchanged since the last successful index pass - nothing to do
            }
            indexEntries(source, fileEntries.getValue());
            indexed++;
            if (currentFingerprint != null) {
                lastIndexedFingerprint.put(fingerprintKey, currentFingerprint);
            }
        }
        return indexed;
    }

    private void indexEntries(LogSource source, List<LogEntry> entries) {
        Map<String, Integer> duplicateOrdinals = new HashMap<>();
        for (LogEntry entry : entries) {
            String contentKey = source.getId() + "|" + entry.file() + "|" + entry.timestamp().toEpochMilli() + "|" + entry.message();
            int ordinal = duplicateOrdinals.merge(contentKey, 1, Integer::sum) - 1;
            String docId = sha256(contentKey) + "::" + ordinal;
            try {
                Document document = documentBuilder.build(source, entry, docId);
                indexWriter.updateDocument(new Term("docId", docId), document);
            } catch (IOException e) {
                log.warn("Failed to index an entry for source {} ('{}'): {}", source.getId(), source.getName(), e.getMessage());
            }
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JVM-mandatory algorithm (JLS/JCA guarantee) - unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
