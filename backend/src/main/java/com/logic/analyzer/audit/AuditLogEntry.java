package com.logic.analyzer.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One admin action against a LogSource/AlertRule/RedactionRule - who did
 * what, when, and the before/after snapshot (see {@link AuditService}).
 * Deliberately has no update()/setters beyond construction: the audit trail
 * is append-only from the API's point of view (no controller ever exposes a
 * write path for this entity besides {@link AuditService#record}).
 */
@Entity
@Table(name = "audit_log_entry")
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditEntityType entityType;

    @Column(nullable = false)
    private Long entityId;

    @Column(nullable = false)
    private String entityName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @Column(nullable = false)
    private String actor;

    /** JSON snapshot of the entity's non-secret fields before the change; null for CREATE. */
    @Column(length = 4000)
    private String oldValue;

    /** JSON snapshot of the entity's non-secret fields after the change; null for DELETE. */
    @Column(length = 4000)
    private String newValue;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    protected AuditLogEntry() {
        // JPA
    }

    public AuditLogEntry(AuditEntityType entityType, Long entityId, String entityName, AuditAction action,
                          String actor, String oldValue, String newValue) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.entityName = entityName;
        this.action = action;
        this.actor = actor;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    @PrePersist
    void onCreate() {
        this.timestamp = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public AuditEntityType getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public String getEntityName() {
        return entityName;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getActor() {
        return actor;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
