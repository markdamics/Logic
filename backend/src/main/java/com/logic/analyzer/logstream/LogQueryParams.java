package com.logic.analyzer.logstream;

import java.util.Set;

public record LogQueryParams(
        String search,
        Set<LogLevel> levels,
        String source,
        String file,
        /** Scopes to entries pattern-mining assigned to this template id (LOGIC-117 drill-down); null means unscoped. */
        Long templateId,
        long rangeMinutes,
        String sortBy,
        String sortDir,
        int page,
        int size
) {
}
