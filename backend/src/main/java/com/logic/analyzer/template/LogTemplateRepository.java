package com.logic.analyzer.template;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface LogTemplateRepository extends JpaRepository<LogTemplate, Long> {

    List<LogTemplate> findBySource(String source);

    List<LogTemplate> findByFile(String file);

    List<LogTemplate> findBySourceAndFile(String source, String file);

    /** LOGIC-118 NEW_PATTERN alert evaluation: candidate templates for a source-wide (no file scope) rule. */
    List<LogTemplate> findBySourceAndFirstSeenAtAfter(String source, Instant cutoff);

    /** LOGIC-118 NEW_PATTERN alert evaluation: candidate templates for a file-scoped rule. */
    List<LogTemplate> findBySourceAndFileAndFirstSeenAtAfter(String source, String file, Instant cutoff);

    /**
     * Unlike deleteById (already @Transactional on SimpleJpaRepository itself), a custom
     * derived delete query runs as a find-then-remove-each loop that needs its own write
     * transaction - without this, TemplateRetentionJob's scheduled call fails with "No
     * EntityManager with actual transaction available ... cannot reliably process 'remove'".
     */
    @Transactional
    long deleteByLastSeenAtBefore(Instant cutoff);
}
