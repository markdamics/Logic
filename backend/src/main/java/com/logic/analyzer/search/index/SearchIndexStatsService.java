package com.logic.analyzer.search.index;

import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;

/**
 * Read-only view into the search index's on-disk footprint and age, backing
 * the Dashboard's storage-usage/oldest-entry stats. Doesn't touch the writer
 * or the retention purge job - just inspects whatever's already committed.
 */
@Service
public class SearchIndexStatsService {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexStatsService.class);

    private final Directory directory;
    private final SearcherManager searcherManager;

    public SearchIndexStatsService(Directory searchIndexDirectory, SearcherManager searcherManager) {
        this.directory = searchIndexDirectory;
        this.searcherManager = searcherManager;
    }

    public long sizeInBytes() {
        try {
            long total = 0;
            for (String name : directory.listAll()) {
                total += directory.fileLength(name);
            }
            return total;
        } catch (IOException e) {
            log.warn("Failed to compute search index size: {}", e.getMessage());
            return 0;
        }
    }

    /** Null when the index has no entries yet. */
    public Instant oldestEntryTimestamp() {
        IndexSearcher searcher;
        try {
            searcher = searcherManager.acquire();
        } catch (IOException e) {
            log.warn("Failed to acquire index searcher for oldest-entry lookup: {}", e.getMessage());
            return null;
        }
        try {
            IndexReader reader = searcher.getIndexReader();
            byte[] min = PointValues.getMinPackedValue(reader, "timestampMillis");
            return min == null ? null : Instant.ofEpochMilli(LongPoint.decodeDimension(min, 0));
        } catch (IOException e) {
            log.warn("Failed to read oldest indexed entry timestamp: {}", e.getMessage());
            return null;
        } finally {
            try {
                searcherManager.release(searcher);
            } catch (IOException e) {
                log.warn("Failed to release index searcher: {}", e.getMessage());
            }
        }
    }
}
