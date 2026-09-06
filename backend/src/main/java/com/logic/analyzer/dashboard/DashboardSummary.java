package com.logic.analyzer.dashboard;

import com.logic.analyzer.logstream.LogEntry;

import java.time.Instant;
import java.util.List;

public record DashboardSummary(
        int totalSources,
        int reachableSources,
        int unreachableSources,
        int unverifiedSources,
        int enabledSources,
        int disabledSources,
        long entriesLast24h,
        long errorsLast24h,
        long warningsLast24h,
        List<LogEntry> recentIssues,
        List<SourceActivity> sourceActivity,
        List<FileActivity> fileActivity,
        long searchIndexSizeBytes,
        /** Null when the search index has no entries yet. */
        Instant oldestRetainedEntryAt
) {
}
