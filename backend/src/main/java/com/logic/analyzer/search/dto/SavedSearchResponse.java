package com.logic.analyzer.search.dto;

import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.search.SavedSearch;
import com.logic.analyzer.search.query.QueryLanguage;

import java.time.Instant;
import java.util.Set;

public record SavedSearchResponse(
        Long id,
        String name,
        QueryLanguage queryLanguage,
        String query,
        String search,
        Set<LogLevel> levels,
        String source,
        String file,
        long rangeMinutes,
        String sortBy,
        String sortDir,
        Instant createdAt
) {
    public static SavedSearchResponse from(SavedSearch savedSearch) {
        return new SavedSearchResponse(
                savedSearch.getId(),
                savedSearch.getName(),
                savedSearch.getQueryLanguage(),
                savedSearch.getQuery(),
                savedSearch.getSearch(),
                savedSearch.getLevels(),
                savedSearch.getSource(),
                savedSearch.getFile(),
                savedSearch.getRangeMinutes(),
                savedSearch.getSortBy(),
                savedSearch.getSortDir(),
                savedSearch.getCreatedAt()
        );
    }
}
