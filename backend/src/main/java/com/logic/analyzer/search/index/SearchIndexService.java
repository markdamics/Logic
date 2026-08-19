package com.logic.analyzer.search.index;

import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.logstream.LogIngestionService;
import com.logic.analyzer.logstream.ingest.TailSource;
import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.LogSourceRepository;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
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
 * Each (source, file) reindex pass clears everything previously indexed for
 * that file and writes its current tail-window read fresh (see
 * {@link #indexEntries}), so the index always reflects a file's *current*
 * content exactly rather than accumulating every version it's ever had. The
 * in-memory per-file fingerprint is only a cheap gate to skip re-processing
 * files that haven't changed - losing it on restart costs one redundant
 * (bounded, cheap) reindex pass, not a correctness bug.
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
            indexEntries(source, fileEntries.getKey(), fileEntries.getValue());
            indexed++;
            if (currentFingerprint != null) {
                lastIndexedFingerprint.put(fingerprintKey, currentFingerprint);
            }
        }
        return indexed;
    }

    /**
     * Each pass re-reads a file's *entire* current tail window, not just what
     * changed since last time - so a line that scrolled out of the window (or
     * a source file that got rewritten/rotated rather than purely appended
     * to) must have its old document removed here, or it lingers in the index
     * forever alongside its replacement. updateDocument's per-line Term upsert
     * only ever replaces or adds; it can never notice "this line is gone now" -
     * which is exactly what let a live source's document count grow far past
     * its file's actual line count. Clearing everything previously indexed for
     * this (source, file) before writing the current batch fresh makes each
     * pass reflect the file's current content exactly, not an ever-growing
     * superset of every version that file has ever had.
     */
    private void indexEntries(LogSource source, String file, List<LogEntry> entries) {
        Query staleQuery = new BooleanQuery.Builder()
                .add(new TermQuery(new Term("source", source.getName())), BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term("file", file)), BooleanClause.Occur.MUST)
                .build();
        try {
            indexWriter.deleteDocuments(staleQuery);
        } catch (IOException e) {
            log.warn("Failed to clear stale index entries for source {} ('{}') file '{}': {}",
                    source.getId(), source.getName(), file, e.getMessage());
        }

        Map<String, Integer> duplicateOrdinals = new HashMap<>();
        for (LogEntry entry : entries) {
            String contentKey = source.getId() + "|" + entry.file() + "|" + entry.timestamp().toEpochMilli() + "|" + entry.message();
            int ordinal = duplicateOrdinals.merge(contentKey, 1, Integer::sum) - 1;
            String docId = sha256(contentKey) + "::" + ordinal;
            try {
                Document document = documentBuilder.build(source, entry, docId);
                indexWriter.addDocument(document);
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
