package com.logic.analyzer.search.query;

/**
 * Which query-bar syntax a raw query string should be parsed as. {@code SIMPLE}
 * is not a query-bar syntax at all - it marks a SavedSearch as a snapshot of the
 * plain-filter UI (search/levels) rather than a raw query string, and is never
 * registered with a QueryParser.
 */
public enum QueryLanguage {
    LUCENE,
    SPL,
    LOGQL,
    SIMPLE
}
