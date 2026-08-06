package com.logic.analyzer.logstream;

import java.util.Set;

public record LogQueryParams(
        String search,
        Set<LogLevel> levels,
        String source,
        long rangeMinutes,
        String sortBy,
        String sortDir,
        int page,
        int size
) {
}
