package com.logic.analyzer.logstream.dto;

import com.logic.analyzer.logstream.LogEntry;

import java.util.List;

/** {@code aggregation} is null for a plain row query; when a query-bar query ends in an aggregation stage, {@code content} is empty and this is populated instead. */
public record LogQueryResult(
        List<LogEntry> content,
        int page,
        int size,
        int totalElements,
        int totalPages,
        LogAggregationResult aggregation
) {
}
