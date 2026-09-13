package com.logic.analyzer.search.nlquery;

import com.logic.analyzer.search.query.QueryLanguage;

/**
 * Result of translating a plain-English prompt into a query-bar string.
 * {@code matched=false} means no template covered the prompt (or the
 * generated candidate failed validation against its own QueryParser) -
 * {@code query} and {@code rangeMinutes} are null in that case, and
 * {@code message} explains why, so the frontend can show it and leave the
 * query bar for the admin to type themselves. The query is never
 * auto-executed by this endpoint either way - it's only ever returned for
 * the caller to fill into the (editable) query bar.
 */
public record NlQueryTranslateResponse(boolean matched, QueryLanguage queryLanguage, String query,
                                        Long rangeMinutes, String message) {

    static NlQueryTranslateResponse unmatched(QueryLanguage language, String message) {
        return new NlQueryTranslateResponse(false, language, null, null, message);
    }
}
