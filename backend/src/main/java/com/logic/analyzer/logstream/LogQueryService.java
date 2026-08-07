package com.logic.analyzer.logstream;

import com.logic.analyzer.logstream.dto.LogQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class LogQueryService {

    private static final Logger log = LoggerFactory.getLogger(LogQueryService.class);

    private final LogIngestionService ingestionService;

    public LogQueryService(LogIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    public LogQueryResult query(LogQueryParams params) {
        List<LogEntry> all = ingestionService.collectEntries();

        // rangeMinutes <= 0 means "all time" - no cutoff - rather than an empty window.
        Instant cutoff = params.rangeMinutes() <= 0
                ? Instant.MIN
                : Instant.now().minus(Duration.ofMinutes(params.rangeMinutes()));
        String term = params.search() == null ? "" : params.search().trim().toLowerCase(Locale.ROOT);

        List<LogEntry> filtered = all.stream()
                .filter(entry -> !entry.timestamp().isBefore(cutoff))
                .filter(entry -> params.levels() == null || params.levels().isEmpty() || params.levels().contains(entry.level()))
                .filter(entry -> params.source() == null || params.source().isBlank() || params.source().equals(entry.source()))
                .filter(entry -> params.file() == null || params.file().isBlank() || params.file().equals(entry.file()))
                .filter(entry -> term.isEmpty() || matches(entry, term))
                .toList();

        Comparator<LogEntry> comparator = comparatorFor(params.sortBy());
        if ("desc".equalsIgnoreCase(params.sortDir())) {
            comparator = comparator.reversed();
        }
        List<LogEntry> sorted = filtered.stream().sorted(comparator).toList();

        int size = Math.max(1, params.size());
        int totalElements = sorted.size();
        int totalPages = (int) Math.ceil(totalElements / (double) size);
        int fromIndex = Math.min(Math.max(params.page(), 0) * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);
        List<LogEntry> content = sorted.subList(fromIndex, toIndex);

        log.debug("Log query matched {} of {} ingested entries (page {} of {}, {} returned)",
                totalElements, all.size(), params.page(), totalPages, content.size());

        return new LogQueryResult(content, params.page(), size, totalElements, totalPages);
    }

    /** Distinct, sorted file labels seen across ingested entries, optionally scoped to one source. */
    public List<String> listFiles(String source) {
        return ingestionService.collectEntries().stream()
                .filter(entry -> source == null || source.isBlank() || source.equals(entry.source()))
                .map(LogEntry::file)
                .filter(file -> file != null && !file.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private boolean matches(LogEntry entry, String term) {
        String haystack = (entry.message() + " " + entry.source() + " " + entry.level()
                + " " + (entry.file() == null ? "" : entry.file()))
                .toLowerCase(Locale.ROOT);
        return haystack.contains(term);
    }

    private Comparator<LogEntry> comparatorFor(String sortBy) {
        return switch (sortBy == null ? "time" : sortBy) {
            case "level" -> Comparator.comparingInt(entry -> entry.level().ordinal());
            case "source" -> Comparator.comparing(LogEntry::source, String.CASE_INSENSITIVE_ORDER);
            case "file" -> Comparator.comparing(entry -> entry.file() == null ? "" : entry.file(), String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparing(LogEntry::timestamp);
        };
    }
}
