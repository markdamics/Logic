package com.logic.analyzer.alert;

/** THRESHOLD covers both "error spikes" and "specific patterns" - a pattern alert is just a threshold rule whose filter is a regex/text match with threshold "count >= 1". */
public enum AlertRuleType {
    THRESHOLD,
    ANOMALY
}
