package com.logic.analyzer.alert.dto;

import com.logic.analyzer.alert.AlertMetric;
import com.logic.analyzer.alert.AlertRuleType;
import com.logic.analyzer.alert.ComparisonOperator;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.search.query.QueryLanguage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Set;

/**
 * Which fields are meaningful depends on {@code queryLanguage} (SIMPLE uses
 * search/levels, LUCENE/SPL/LOGQL use query) and {@code ruleType}
 * (THRESHOLD uses comparisonOp/threshold, ANOMALY uses the anomaly*
 * fields) - conditional requirements Bean Validation can't express, so
 * they're checked in AlertRuleService.
 */
public record AlertRuleCreateRequest(
        @NotBlank String name,
        @NotNull QueryLanguage queryLanguage,
        String query,
        String search,
        Set<LogLevel> levels,
        String source,
        String file,
        @NotNull AlertRuleType ruleType,
        @Positive int windowMinutes,
        @NotNull AlertMetric metric,
        ComparisonOperator comparisonOp,
        Double threshold,
        Integer anomalyBaselineWindows,
        Double anomalyStdDevMultiplier,
        String webhookUrl,
        String webhookSecret
) {
}
