package com.logic.analyzer.alert;

import com.logic.analyzer.crypto.EncryptedStringConverter;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.search.query.QueryLanguage;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A watch rule over a subset of indexed logs (the scope fields mirror
 * {@link com.logic.analyzer.search.SavedSearch}'s shape exactly, but are a
 * deliberate copy rather than a foreign key - deleting a saved search must
 * never silently break an alert). THRESHOLD rules compare a window's
 * count/rate against a fixed number; ANOMALY rules compare it against a
 * statistical baseline (mean + k*stddev of prior windows) computed by
 * {@link AlertEvaluationService} - see that class for the evaluation logic.
 */
@Entity
@Table(name = "alert_rule")
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QueryLanguage queryLanguage;

    /** Raw query-bar string for a LUCENE/SPL/LOGQL rule; null for a SIMPLE filter-snapshot rule. */
    private String query;

    /** Free-text filter for a SIMPLE rule; null otherwise. */
    private String search;

    /** Comma-joined {@link LogLevel} names for a SIMPLE rule's severity filter; null/empty otherwise. */
    private String levels;

    private String source;

    private String file;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertRuleType ruleType;

    @Column(nullable = false)
    private int windowMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertMetric metric;

    /** THRESHOLD only. */
    @Enumerated(EnumType.STRING)
    private ComparisonOperator comparisonOp;

    /** THRESHOLD only. */
    private Double threshold;

    /** ANOMALY only - how many prior windows form the baseline. */
    private Integer anomalyBaselineWindows;

    /** ANOMALY only - how many standard deviations above the baseline mean counts as anomalous. */
    private Double anomalyStdDevMultiplier;

    /** Muted rules are still evaluated (lastTriggeredAt stays live) but never fire a webhook. */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean muted = false;

    private Instant lastTriggeredAt;

    private Instant lastEvaluatedAt;

    private String webhookUrl;

    /** Encrypted at rest (AES-256-GCM) via {@link EncryptedStringConverter}, same as LogSource's SFTP password. */
    @Convert(converter = EncryptedStringConverter.class)
    private String webhookSecret;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AlertRule() {
        // JPA
    }

    public AlertRule(String name, QueryLanguage queryLanguage, String query, String search, Set<LogLevel> levels,
                      String source, String file, AlertRuleType ruleType, int windowMinutes, AlertMetric metric,
                      ComparisonOperator comparisonOp, Double threshold, Integer anomalyBaselineWindows,
                      Double anomalyStdDevMultiplier, String webhookUrl, String webhookSecret) {
        this.name = name;
        this.queryLanguage = queryLanguage;
        this.query = query;
        this.search = search;
        this.levels = joinLevels(levels);
        this.source = source;
        this.file = file;
        this.ruleType = ruleType;
        this.windowMinutes = windowMinutes;
        this.metric = metric;
        this.comparisonOp = comparisonOp;
        this.threshold = threshold;
        this.anomalyBaselineWindows = anomalyBaselineWindows;
        this.anomalyStdDevMultiplier = anomalyStdDevMultiplier;
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    private static String joinLevels(Set<LogLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return null;
        }
        return levels.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    /**
     * Replaces the editable fields. Password-style handling for the webhook
     * secret: only overwritten when a new one is supplied, so editing other
     * fields doesn't force re-entering a still-valid secret.
     */
    public void update(String name, QueryLanguage queryLanguage, String query, String search, Set<LogLevel> levels,
                        String source, String file, AlertRuleType ruleType, int windowMinutes, AlertMetric metric,
                        ComparisonOperator comparisonOp, Double threshold, Integer anomalyBaselineWindows,
                        Double anomalyStdDevMultiplier, String webhookUrl, String webhookSecret) {
        this.name = name;
        this.queryLanguage = queryLanguage;
        this.query = query;
        this.search = search;
        this.levels = joinLevels(levels);
        this.source = source;
        this.file = file;
        this.ruleType = ruleType;
        this.windowMinutes = windowMinutes;
        this.metric = metric;
        this.comparisonOp = comparisonOp;
        this.threshold = threshold;
        this.anomalyBaselineWindows = anomalyBaselineWindows;
        this.anomalyStdDevMultiplier = anomalyStdDevMultiplier;
        this.webhookUrl = webhookUrl;
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            this.webhookSecret = webhookSecret;
        }
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public QueryLanguage getQueryLanguage() {
        return queryLanguage;
    }

    public String getQuery() {
        return query;
    }

    public String getSearch() {
        return search;
    }

    public Set<LogLevel> getLevels() {
        if (levels == null || levels.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(levels.split(","))
                .map(LogLevel::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public String getSource() {
        return source;
    }

    public String getFile() {
        return file;
    }

    public AlertRuleType getRuleType() {
        return ruleType;
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public AlertMetric getMetric() {
        return metric;
    }

    public ComparisonOperator getComparisonOp() {
        return comparisonOp;
    }

    public Double getThreshold() {
        return threshold;
    }

    public Integer getAnomalyBaselineWindows() {
        return anomalyBaselineWindows;
    }

    public Double getAnomalyStdDevMultiplier() {
        return anomalyStdDevMultiplier;
    }

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public Instant getLastTriggeredAt() {
        return lastTriggeredAt;
    }

    public void setLastTriggeredAt(Instant lastTriggeredAt) {
        this.lastTriggeredAt = lastTriggeredAt;
    }

    public Instant getLastEvaluatedAt() {
        return lastEvaluatedAt;
    }

    public void setLastEvaluatedAt(Instant lastEvaluatedAt) {
        this.lastEvaluatedAt = lastEvaluatedAt;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
