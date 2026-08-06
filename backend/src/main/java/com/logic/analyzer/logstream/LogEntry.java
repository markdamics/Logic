package com.logic.analyzer.logstream;

import java.time.Instant;

public record LogEntry(long id, Instant timestamp, LogLevel level, String source, String message) {
}
