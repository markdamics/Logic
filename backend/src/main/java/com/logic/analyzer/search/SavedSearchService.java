package com.logic.analyzer.search;

import com.logic.analyzer.exception.SavedSearchNotFoundException;
import com.logic.analyzer.logstream.LogQueryParams;
import com.logic.analyzer.logstream.LogQueryService;
import com.logic.analyzer.logstream.dto.LogQueryResult;
import com.logic.analyzer.search.dto.SavedSearchCreateRequest;
import com.logic.analyzer.search.dto.SavedSearchResponse;
import com.logic.analyzer.search.query.QueryLanguage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SavedSearchService {

    private static final Logger log = LoggerFactory.getLogger(SavedSearchService.class);

    private final SavedSearchRepository repository;
    private final LogQueryService logQueryService;
    private final SearchQueryService searchQueryService;

    public SavedSearchService(SavedSearchRepository repository, LogQueryService logQueryService,
                               SearchQueryService searchQueryService) {
        this.repository = repository;
        this.logQueryService = logQueryService;
        this.searchQueryService = searchQueryService;
    }

    public List<SavedSearchResponse> listAll() {
        return repository.findAll().stream().map(SavedSearchResponse::from).toList();
    }

    public SavedSearchResponse create(SavedSearchCreateRequest request) {
        if (request.queryLanguage() != QueryLanguage.SIMPLE
                && (request.query() == null || request.query().isBlank())) {
            throw new IllegalArgumentException("query is required for a " + request.queryLanguage() + " saved search");
        }

        SavedSearch savedSearch = new SavedSearch(
                request.name(),
                request.queryLanguage(),
                request.query(),
                request.search(),
                request.levels(),
                request.source(),
                request.file(),
                request.rangeMinutes(),
                request.sortBy() == null || request.sortBy().isBlank() ? "time" : request.sortBy(),
                request.sortDir() == null || request.sortDir().isBlank() ? "desc" : request.sortDir()
        );
        SavedSearch saved = repository.save(savedSearch);
        log.info("Created saved search '{}' (id={}, queryLanguage={})",
                saved.getName(), saved.getId(), saved.getQueryLanguage());
        return SavedSearchResponse.from(saved);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new SavedSearchNotFoundException(id);
        }
        repository.deleteById(id);
        log.info("Deleted saved search {}", id);
    }

    /**
     * A SIMPLE save is a snapshot of the plain-filter UI, run through the same
     * LogQueryService the Log Stream's own filters use; every other language
     * is a raw query-bar string, run through SearchQueryService like the query
     * bar itself - one saved search, the same two execution paths the UI already has.
     */
    public LogQueryResult run(Long id, int page, int size) {
        SavedSearch savedSearch = repository.findById(id).orElseThrow(() -> new SavedSearchNotFoundException(id));
        log.info("Running saved search {} ('{}', {})", id, savedSearch.getName(), savedSearch.getQueryLanguage());

        if (savedSearch.getQueryLanguage() == QueryLanguage.SIMPLE) {
            return logQueryService.query(new LogQueryParams(
                    savedSearch.getSearch(),
                    savedSearch.getLevels(),
                    savedSearch.getSource(),
                    savedSearch.getFile(),
                    savedSearch.getRangeMinutes(),
                    savedSearch.getSortBy(),
                    savedSearch.getSortDir(),
                    page,
                    size
            ));
        }

        return searchQueryService.query(
                savedSearch.getQuery(),
                savedSearch.getQueryLanguage(),
                savedSearch.getSource(),
                savedSearch.getFile(),
                savedSearch.getRangeMinutes(),
                savedSearch.getSortBy(),
                savedSearch.getSortDir(),
                page,
                size
        );
    }
}
