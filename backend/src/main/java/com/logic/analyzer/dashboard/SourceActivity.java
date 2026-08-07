package com.logic.analyzer.dashboard;

import com.logic.analyzer.source.SourceStatus;

public record SourceActivity(String source, SourceStatus status, boolean enabled, boolean live, long entriesLast24h, long errorsLast24h) {
}
