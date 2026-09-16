package com.logic.analyzer.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One trigger-to-resolve span for an {@link AlertRule} - gives
 * lastTriggeredAt a real audit trail instead of a single overwritable field.
 * No FK constraint to alert_rule, matching this schema's existing informal-
 * reference style (see log_source/saved_search).
 */
@Entity
@Table(name = "alert_event")
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long alertRuleId;

    @Column(nullable = false)
    private Instant triggeredAt;

    /** Set once the condition next evaluates false. */
    private Instant resolvedAt;

    @Column(nullable = false)
    private double metricValue;

    /** Snapshot of the rule's threshold at trigger time, in case the rule's threshold changes later. */
    private Double thresholdAtTrigger;

    /** HTTP status from the webhook delivery attempt, or null if no webhook was configured or the request failed outright. */
    private Integer webhookStatus;

    /** NEW_PATTERN only - the {@link com.logic.analyzer.template.LogTemplate} that fired this event, also doubling as the dedup key so a template never fires twice for the same rule. */
    private Long templateId;

    /** NEW_PATTERN only - snapshot of the template's text at trigger time, in case the template merges/widens later. */
    @Column(length = 2000)
    private String templateText;

    /** NEW_PATTERN only - a sample raw line, same purpose as {@link com.logic.analyzer.template.LogTemplate#getSampleRawLine()}. */
    @Column(length = 2000)
    private String sampleRawLine;

    protected AlertEvent() {
        // JPA
    }

    public AlertEvent(Long alertRuleId, Instant triggeredAt, double metricValue, Double thresholdAtTrigger) {
        this.alertRuleId = alertRuleId;
        this.triggeredAt = triggeredAt;
        this.metricValue = metricValue;
        this.thresholdAtTrigger = thresholdAtTrigger;
    }

    /** NEW_PATTERN firing - carries the newly appeared template's identity/text instead of a numeric threshold comparison. */
    public AlertEvent(Long alertRuleId, Instant triggeredAt, double metricValue, Long templateId, String templateText, String sampleRawLine) {
        this.alertRuleId = alertRuleId;
        this.triggeredAt = triggeredAt;
        this.metricValue = metricValue;
        this.templateId = templateId;
        this.templateText = templateText;
        this.sampleRawLine = sampleRawLine;
    }

    public Long getId() {
        return id;
    }

    public Long getAlertRuleId() {
        return alertRuleId;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public double getMetricValue() {
        return metricValue;
    }

    public Double getThresholdAtTrigger() {
        return thresholdAtTrigger;
    }

    public Integer getWebhookStatus() {
        return webhookStatus;
    }

    public void setWebhookStatus(Integer webhookStatus) {
        this.webhookStatus = webhookStatus;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public String getTemplateText() {
        return templateText;
    }

    public String getSampleRawLine() {
        return sampleRawLine;
    }
}
