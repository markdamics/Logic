package com.logic.analyzer.alert.dto;

import com.logic.analyzer.alert.AlertMetric;
import com.logic.analyzer.alert.AlertRule;
import com.logic.analyzer.alert.AlertRuleType;
import com.logic.analyzer.alert.ComparisonOperator;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.search.query.QueryLanguage;

import java.time.Instant;
import java.util.Set;

/** Mirrors {@link AlertRule} but omits the webhook secret - {@code webhookSecretConfigured} says whether one is set, without revealing it. */
public record AlertRuleResponse(
        Long id,
        String name,
        QueryLanguage queryLanguage,
        String query,
        String search,
        Set<LogLevel> levels,
        String source,
        String file,
        AlertRuleType ruleType,
        int windowMinutes,
        AlertMetric metric,
        ComparisonOperator comparisonOp,
        Double threshold,
        Integer anomalyBaselineWindows,
        Double anomalyStdDevMultiplier,
        boolean muted,
        Instant lastTriggeredAt,
        Instant lastEvaluatedAt,
        String webhookUrl,
        boolean webhookSecretConfigured,
        Instant createdAt
) {
    public static AlertRuleResponse from(AlertRule rule) {
        return new AlertRuleResponse(
                rule.getId(),
                rule.getName(),
                rule.getQueryLanguage(),
                rule.getQuery(),
                rule.getSearch(),
                rule.getLevels(),
                rule.getSource(),
                rule.getFile(),
                rule.getRuleType(),
                rule.getWindowMinutes(),
                rule.getMetric(),
                rule.getComparisonOp(),
                rule.getThreshold(),
                rule.getAnomalyBaselineWindows(),
                rule.getAnomalyStdDevMultiplier(),
                rule.isMuted(),
                rule.getLastTriggeredAt(),
                rule.getLastEvaluatedAt(),
                rule.getWebhookUrl(),
                rule.getWebhookSecret() != null,
                rule.getCreatedAt()
        );
    }
}
