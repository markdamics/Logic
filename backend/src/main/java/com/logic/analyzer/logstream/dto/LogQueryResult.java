package com.logic.analyzer.logstream.dto;

import com.logic.analyzer.logstream.LogEntry;

import java.util.List;

public record LogQueryResult(
        List<LogEntry> content,
        int page,
        int size,
        int totalElements,
        int totalPages
) {
}
