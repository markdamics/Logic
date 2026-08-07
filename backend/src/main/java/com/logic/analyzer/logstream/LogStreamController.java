package com.logic.analyzer.logstream;

import com.logic.analyzer.logstream.dto.LogQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/logs")
public class LogStreamController {

    private static final Logger log = LoggerFactory.getLogger(LogStreamController.class);

    private final LogQueryService queryService;

    public LogStreamController(LogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public LogQueryResult list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Set<LogLevel> level,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String file,
            @RequestParam(defaultValue = "0") long rangeMinutes,
            @RequestParam(defaultValue = "time") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        log.info("GET /api/logs search='{}' level={} source='{}' file='{}' rangeMinutes={} sortBy={} sortDir={} page={} size={}",
                search, level, source, file, rangeMinutes, sortBy, sortDir, page, size);

        Set<LogLevel> levels = level == null ? Set.of() : level;
        LogQueryResult result = queryService.query(
                new LogQueryParams(search, levels, source, file, rangeMinutes, sortBy, sortDir, page, size));

        log.debug("GET /api/logs -> {} of {} total", result.content().size(), result.totalElements());
        return result;
    }

    @GetMapping("/files")
    public List<String> listFiles(@RequestParam(required = false) String source) {
        log.info("GET /api/logs/files source='{}'", source);
        return queryService.listFiles(source);
    }

    @PostMapping("/reload")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reload() {
        log.info("POST /api/logs/reload");
        queryService.reload();
    }
}
