package com.logic.analyzer.dashboard;

public record FileActivity(String file, String source, long entriesLast24h, long errorsLast24h) {
}
