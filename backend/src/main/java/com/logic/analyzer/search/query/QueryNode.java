package com.logic.analyzer.search.query;

import org.apache.lucene.search.Query;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Common query AST shared by the simple-filter translation (LogQueryService)
 * and every query-language parser (Lucene syntax, SPL-subset, LogQL-subset).
 * One AST, one {@link QueryCompiler}, one Lucene-backed executor - so every
 * entry point benefits from the same indexed search rather than each having
 * its own filtering logic.
 */
public sealed interface QueryNode {

    /** No filters at all - matches every indexed entry. */
    record MatchAllNode() implements QueryNode {
    }

    /** A bare keyword, matched (tokenized) against the composite "_all" field. */
    record TermNode(String text) implements QueryNode {
    }

    /** An exact phrase, tokenized the same way as TermNode but matched as a contiguous sequence. {@code field} null means "_all". */
    record PhraseNode(String field, String phrase) implements QueryNode {
    }

    /**
     * @param exact true for an exact (untokenized) match against a keyword field
     *              (e.g. level/source/file); false to run the value through the
     *              same analyzed matching TermNode uses.
     */
    record FieldMatchNode(String field, String value, boolean exact) implements QueryNode {
    }

    /** Inclusive/exclusive numeric range on a LongPoint field (e.g. timestampMillis). Null bound = unbounded on that side. */
    record RangeNode(String field, Long lower, Long upper, boolean lowerInclusive, boolean upperInclusive) implements QueryNode {
    }

    /** Same as RangeNode but for a DoublePoint field (the "field.&lt;name&gt;#num" siblings SPL-subset comparisons target). */
    record NumericFieldRangeNode(String field, Double lower, Double upper, boolean lowerInclusive, boolean upperInclusive) implements QueryNode {
    }

    /** Regex match against a field's raw term text. {@code field} null means "message". */
    record RegexNode(String field, String pattern) implements QueryNode {
    }

    record AndNode(List<QueryNode> children) implements QueryNode {
    }

    record OrNode(List<QueryNode> children) implements QueryNode {
    }

    record NotNode(QueryNode child) implements QueryNode {
    }

    /** Escape hatch for a query built directly with Lucene APIs (e.g. the classic QueryParser's own output). */
    record NativeLuceneNode(Query query) implements QueryNode {
    }

    /**
     * The source/file/time-range scoping every entry point shares - the plain
     * simple-filter UI (LogQueryService) and the query-bar (SearchQueryService),
     * which ANDs this with whatever its query-language parser produced. Kept
     * here rather than duplicated so both stay in lockstep.
     *
     * @param excludedSources names of currently-disabled sources - a MUST_NOT
     *                        clause per name, so a disabled source's already-indexed
     *                        entries stop showing up everywhere (Log Stream, query-bar,
     *                        alerts) the moment it's disabled, without purging them
     *                        from the index (re-enabling is then instant, not a
     *                        from-scratch re-ingest).
     */
    static List<QueryNode> scopeClauses(String source, String file, long rangeMinutes, List<String> excludedSources) {
        List<QueryNode> clauses = new ArrayList<>();

        // rangeMinutes <= 0 means "all time" - no cutoff - rather than an empty window.
        if (rangeMinutes > 0) {
            long cutoffMillis = Instant.now().minus(Duration.ofMinutes(rangeMinutes)).toEpochMilli();
            clauses.add(new RangeNode("timestampMillis", cutoffMillis, null, true, true));
        }
        if (source != null && !source.isBlank()) {
            clauses.add(new FieldMatchNode("source", source, true));
        }
        if (file != null && !file.isBlank()) {
            clauses.add(new FieldMatchNode("file", file, true));
        }
        for (String excluded : excludedSources) {
            clauses.add(new NotNode(new FieldMatchNode("source", excluded, true)));
        }
        return clauses;
    }
}
