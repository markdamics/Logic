package com.logic.analyzer.search;

import com.logic.analyzer.logstream.dto.LogQueryResult;
import com.logic.analyzer.search.query.QueryLanguage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The query-bar endpoint, alongside LogStreamController's plain-filter
 * /api/logs - a separate controller so the query-language feature stays a
 * self-contained addition, sharing the same /api/logs base path.
 */
@RestController
@RequestMapping("/api/logs")
public class SearchQueryController {

    private static final Logger log = LoggerFactory.getLogger(SearchQueryController.class);

    private final SearchQueryService searchQueryService;

    public SearchQueryController(SearchQueryService searchQueryService) {
        this.searchQueryService = searchQueryService;
    }

    @GetMapping("/query")
    public LogQueryResult query(
            @RequestParam String q,
            @RequestParam(defaultValue = "LUCENE") QueryLanguage queryLanguage,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String file,
            @RequestParam(defaultValue = "0") long rangeMinutes,
            @RequestParam(defaultValue = "time") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.info("GET /api/logs/query q='{}' queryLanguage={} source='{}' file='{}' rangeMinutes={} sortBy={} sortDir={} page={} size={}",
                q, queryLanguage, source, file, rangeMinutes, sortBy, sortDir, page, size);
        return searchQueryService.query(q, queryLanguage, source, file, rangeMinutes, sortBy, sortDir, page, size);
    }
}
