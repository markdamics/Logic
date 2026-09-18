package com.logic.analyzer.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {

    /** Capped so the read-only Audit screen never has to render/transfer an unbounded history. */
    List<AuditLogEntry> findTop500ByOrderByTimestampDesc();
}
