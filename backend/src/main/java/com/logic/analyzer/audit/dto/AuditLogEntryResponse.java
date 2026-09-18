package com.logic.analyzer.audit.dto;

import com.logic.analyzer.audit.AuditAction;
import com.logic.analyzer.audit.AuditEntityType;
import com.logic.analyzer.audit.AuditLogEntry;

import java.time.Instant;

/** {@code oldValue}/{@code newValue} are pre-serialized JSON strings (or null), rendered as-is by the frontend. */
public record AuditLogEntryResponse(
        Long id,
        AuditEntityType entityType,
        Long entityId,
        String entityName,
        AuditAction action,
        String actor,
        String oldValue,
        String newValue,
        Instant timestamp
) {
    public static AuditLogEntryResponse from(AuditLogEntry entry) {
        return new AuditLogEntryResponse(
                entry.getId(),
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getEntityName(),
                entry.getAction(),
                entry.getActor(),
                entry.getOldValue(),
                entry.getNewValue(),
                entry.getTimestamp()
        );
    }
}
