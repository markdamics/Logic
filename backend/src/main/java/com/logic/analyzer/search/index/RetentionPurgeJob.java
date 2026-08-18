package com.logic.analyzer.search.index;

import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Ages out indexed entries older than the configured retention window. The
 * index has no other size cap, so this is what keeps it from growing
 * unbounded now that it's durable (unlike the old tail-window cache, which
 * had an implicit cap from only ever holding whatever fit in the last
 * 512KB/3000 lines of each file).
 */
@Component
public class RetentionPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeJob.class);

    private final IndexWriter indexWriter;
    private final SearcherManager searcherManager;
    private final long retentionDays;

    public RetentionPurgeJob(IndexWriter indexWriter, SearcherManager searcherManager,
                              @Value("${app.search.retention-days:30}") long retentionDays) {
        this.indexWriter = indexWriter;
        this.searcherManager = searcherManager;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${app.search.purge-interval-ms:3600000}")
    public void purgeExpired() {
        if (retentionDays <= 0) {
            return; // unlimited retention
        }
        long cutoffMillis = Instant.now().minus(Duration.ofDays(retentionDays)).toEpochMilli();
        Query expired = LongPoint.newRangeQuery("timestampMillis", Long.MIN_VALUE, cutoffMillis - 1);
        try {
            long before = indexWriter.getDocStats().numDocs;
            indexWriter.deleteDocuments(expired);
            indexWriter.commit();
            searcherManager.maybeRefresh();
            long after = indexWriter.getDocStats().numDocs;
            if (before != after) {
                log.info("Search index retention purge removed entries older than {} days ({} -> {} docs)",
                        retentionDays, before, after);
            }
        } catch (IOException e) {
            log.warn("Search index retention purge failed: {}", e.getMessage());
        }
    }
}
