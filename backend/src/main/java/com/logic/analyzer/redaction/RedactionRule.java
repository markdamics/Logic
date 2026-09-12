package com.logic.analyzer.redaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * A regex-based masking rule applied to log message content at ingest time
 * (see {@link com.logic.analyzer.logstream.LogIngestionService}), before any
 * entry is cached or indexed - the raw matched text is never persisted.
 * {@code source} mirrors {@link com.logic.analyzer.alert.AlertRule}'s scope
 * fields (a name copy, not a foreign key): null/blank applies globally,
 * otherwise only to that source.
 */
@Entity
@Table(name = "redaction_rule")
public class RedactionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 1000)
    private String pattern;

    /** Text substituted for each match; blank/null falls back to a default mask at apply time. */
    private String replacement;

    private String source;

    @Column(nullable = false)
    @ColumnDefault("true")
    private boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected RedactionRule() {
        // JPA
    }

    public RedactionRule(String name, String pattern, String replacement, String source, boolean enabled) {
        this.name = name;
        this.pattern = pattern;
        this.replacement = replacement;
        this.source = source;
        this.enabled = enabled;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public void update(String name, String pattern, String replacement, String source, boolean enabled) {
        this.name = name;
        this.pattern = pattern;
        this.replacement = replacement;
        this.source = source;
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPattern() {
        return pattern;
    }

    public String getReplacement() {
        return replacement;
    }

    public String getSource() {
        return source;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
