package com.logic.analyzer.search.dto;

import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.search.query.QueryLanguage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

/**
 * Which fields are meaningful depends on {@code queryLanguage}: SIMPLE uses
 * search/levels (a snapshot of the plain-filter UI), LUCENE/SPL/LOGQL use
 * query (the raw query-bar string) instead. Bean Validation can't express
 * that conditional requirement, so it's checked in SavedSearchService.create(...).
 */
public record SavedSearchCreateRequest(
        @NotBlank String name,
        @NotNull QueryLanguage queryLanguage,
        String query,
        String search,
        Set<LogLevel> levels,
        String source,
        String file,
        long rangeMinutes,
        String sortBy,
        String sortDir
) {
}
