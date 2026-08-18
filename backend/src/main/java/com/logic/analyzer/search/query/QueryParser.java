package com.logic.analyzer.search.query;

/** One query-bar syntax's translator into the shared {@link QueryNode} AST. */
public interface QueryParser {

    QueryLanguage language();

    /** @throws IllegalArgumentException if the query string isn't valid syntax for this language. */
    ParsedQuery parse(String queryString);
}
