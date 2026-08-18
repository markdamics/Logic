package com.logic.analyzer.search.query;

import java.util.Optional;

/** Result of parsing a query-bar string into the shared AST, plus an optional trailing aggregation stage. */
public record ParsedQuery(QueryNode filter, Optional<AggregationStage> aggregation) {

    /** Convenience for languages/queries with no aggregation stage (e.g. every Lucene-syntax query). */
    public static ParsedQuery filterOnly(QueryNode filter) {
        return new ParsedQuery(filter, Optional.empty());
    }
}
