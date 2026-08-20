package com.logic.analyzer.alert.dto;

import com.logic.analyzer.alert.AlertEvent;

import java.time.Instant;

public record AlertEventResponse(
        Long id,
        Instant triggeredAt,
        Instant resolvedAt,
        double metricValue,
        Double thresholdAtTrigger,
        Integer webhookStatus
) {
    public static AlertEventResponse from(AlertEvent event) {
        return new AlertEventResponse(
                event.getId(),
                event.getTriggeredAt(),
                event.getResolvedAt(),
                event.getMetricValue(),
                event.getThresholdAtTrigger(),
                event.getWebhookStatus()
        );
    }
}
