package com.logic.analyzer.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class TemplateRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(TemplateRetentionJob.class);

    private final LogTemplateRepository repository;
    private final TemplateMiningService miningService;
    private final long retentionDays;

    public TemplateRetentionJob(LogTemplateRepository repository, TemplateMiningService miningService,
                                 @Value("${app.search.retention-days:30}") long retentionDays) {
        this.repository = repository;
        this.miningService = miningService;
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${app.search.purge-interval-ms:3600000}")
    public void purgeExpired() {
        if (retentionDays <= 0) {
            return; // unlimited retention
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        long deleted = repository.deleteByLastSeenAtBefore(cutoff);
        if (deleted > 0) {
            // The in-memory per-source cluster cache may still hold references to rows just
            // deleted; clearing it forces a fresh load from the repository on next use rather
            // than risking a mine() silently no-op-updating a since-deleted row.
            miningService.invalidateCache();
            log.info("Template retention purge removed {} template(s) not seen in {} days", deleted, retentionDays);
        }
    }
}
