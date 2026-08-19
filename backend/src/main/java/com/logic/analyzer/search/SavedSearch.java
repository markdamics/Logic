package com.logic.analyzer.search;

import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.search.query.QueryLanguage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "saved_search")
public class SavedSearch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QueryLanguage queryLanguage;

    /** Raw query-bar string for a LUCENE/SPL/LOGQL save; null for a SIMPLE filter-snapshot save. */
    private String query;

    /** Free-text filter for a SIMPLE save; null otherwise. */
    private String search;

    /** Comma-joined {@link LogLevel} names for a SIMPLE save's severity filter; null/empty otherwise. */
    private String levels;

    private String source;

    private String file;

    @Column(nullable = false)
    @ColumnDefault("0")
    private long rangeMinutes;

    @Column(nullable = false)
    @ColumnDefault("'time'")
    private String sortBy;

    @Column(nullable = false)
    @ColumnDefault("'desc'")
    private String sortDir;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected SavedSearch() {
        // JPA
    }

    public SavedSearch(String name, QueryLanguage queryLanguage, String query, String search, Set<LogLevel> levels,
                        String source, String file, long rangeMinutes, String sortBy, String sortDir) {
        this.name = name;
        this.queryLanguage = queryLanguage;
        this.query = query;
        this.search = search;
        this.levels = joinLevels(levels);
        this.source = source;
        this.file = file;
        this.rangeMinutes = rangeMinutes;
        this.sortBy = sortBy;
        this.sortDir = sortDir;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    private static String joinLevels(Set<LogLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return null;
        }
        return levels.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public QueryLanguage getQueryLanguage() {
        return queryLanguage;
    }

    public String getQuery() {
        return query;
    }

    public String getSearch() {
        return search;
    }

    public Set<LogLevel> getLevels() {
        if (levels == null || levels.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(levels.split(","))
                .map(LogLevel::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public String getSource() {
        return source;
    }

    public String getFile() {
        return file;
    }

    public long getRangeMinutes() {
        return rangeMinutes;
    }

    public String getSortBy() {
        return sortBy;
    }

    public String getSortDir() {
        return sortDir;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
