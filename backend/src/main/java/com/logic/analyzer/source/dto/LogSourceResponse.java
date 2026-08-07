package com.logic.analyzer.source.dto;

import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.SourceStatus;
import com.logic.analyzer.source.SourceType;

import java.time.Instant;
import java.util.List;

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
        boolean enabled,
        boolean live,
        List<String> changedFiles,
        Instant createdAt,
        Instant lastCheckedAt
) {
    public static LogSourceResponse from(LogSource source) {
        return from(source, List.of());
    }

    /** {@code changedFiles} names the file(s) of a non-live source that changed since it was last read. */
    public static LogSourceResponse from(LogSource source, List<String> changedFiles) {
        return new LogSourceResponse(
                source.getId(),
                source.getName(),
                source.getType(),
                source.getPath(),
                source.getHost(),
                source.getPort(),
                source.getUsername(),
                source.getStatus(),
                source.isEnabled(),
                source.isLive(),
                changedFiles,
                source.getCreatedAt(),
                source.getLastCheckedAt()
        );
    }
}
