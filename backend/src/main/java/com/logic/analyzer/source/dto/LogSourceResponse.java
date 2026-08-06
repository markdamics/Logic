package com.logic.analyzer.source.dto;

import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.SourceStatus;
import com.logic.analyzer.source.SourceType;

import java.time.Instant;

/** Mirrors {@link LogSource} but intentionally omits the password field. */
public record LogSourceResponse(
        Long id,
        String name,
        SourceType type,
        String path,
        String host,
        Integer port,
        String username,
        SourceStatus status,
        Instant createdAt,
        Instant lastCheckedAt
) {
    public static LogSourceResponse from(LogSource source) {
        return new LogSourceResponse(
                source.getId(),
                source.getName(),
                source.getType(),
                source.getPath(),
                source.getHost(),
                source.getPort(),
                source.getUsername(),
                source.getStatus(),
                source.getCreatedAt(),
                source.getLastCheckedAt()
        );
    }
}
