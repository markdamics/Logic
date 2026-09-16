package com.logic.analyzer.alert;

/**
 * THRESHOLD covers both "error spikes" and "specific patterns" - a pattern alert is just a
 * threshold rule whose filter is a regex/text match with threshold "count >= 1". NEW_PATTERN is a
 * different shape entirely - see {@link AlertEvaluationService} - it watches
 * {@link com.logic.analyzer.template.LogTemplate#getFirstSeenAt()} rather than a count/rate over
 * a query, so it needs no query/search/level fields, just the shared source scope + window.
 */
public enum AlertRuleType {
    THRESHOLD,
    ANOMALY,
    NEW_PATTERN
}
