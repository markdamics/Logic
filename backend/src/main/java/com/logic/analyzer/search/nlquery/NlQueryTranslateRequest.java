package com.logic.analyzer.search.nlquery;

import com.logic.analyzer.search.query.QueryLanguage;

public record NlQueryTranslateRequest(String prompt, QueryLanguage queryLanguage) {
}
