package com.logic.analyzer.source.dto;

import com.logic.analyzer.source.SourceStatus;

import java.time.Instant;

public record ConnectionTestResult(
        SourceStatus status,
        String message,
        Instant checkedAt
) {
}
