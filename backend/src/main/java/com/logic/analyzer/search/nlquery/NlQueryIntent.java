package com.logic.analyzer.search.nlquery;

import com.logic.analyzer.logstream.LogLevel;

/**
 * Structured intent extracted from a plain-English prompt by
 * {@link NlQueryIntentExtractor} - the input {@link NlQueryTemplateTranslator}
 * turns into a query-bar string. All fields are best-effort and nullable;
 * an empty intent means nothing recognizable was found in the prompt.
 */
public record NlQueryIntent(LogLevel level, String source, String freeText, Long rangeMinutes) {

    public boolean isEmpty() {
        return level == null && source == null && (freeText == null || freeText.isBlank()) && rangeMinutes == null;
    }
}
