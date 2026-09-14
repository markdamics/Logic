package com.logic.analyzer.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface LogTemplateRepository extends JpaRepository<LogTemplate, Long> {

    List<LogTemplate> findBySource(String source);

    long deleteByLastSeenAtBefore(Instant cutoff);
}
