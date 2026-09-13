package com.logic.analyzer.search.nlquery;

import com.logic.analyzer.search.query.QueryLanguage;
import com.logic.analyzer.search.query.QueryParser;
import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.LogSourceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Phase 1 (template-only, no LLM) half of LOGIC-107: turns a plain-English
 * prompt into a candidate query-bar string for the requested QueryLanguage,
 * using {@link NlQueryIntentExtractor}'s best-effort keyword/regex
 * extraction, then validates the candidate through that language's own
 * {@link QueryParser} before ever returning it - so an unrecognized prompt,
 * or one that produces something that doesn't actually parse, comes back as
 * a clear "couldn't translate" result rather than a broken query reaching
 * the query bar.
 */
@Service
public class NlQueryTemplateTranslator {

    private final Map<QueryLanguage, QueryParser> parsersByLanguage;
    private final LogSourceRepository sourceRepository;

    public NlQueryTemplateTranslator(List<QueryParser> parsers, LogSourceRepository sourceRepository) {
        this.parsersByLanguage = new EnumMap<>(QueryLanguage.class);
        for (QueryParser parser : parsers) {
            parsersByLanguage.put(parser.language(), parser);
        }
        this.sourceRepository = sourceRepository;
    }

    public NlQueryTranslateResponse translate(String prompt, QueryLanguage language) {
        QueryParser parser = parsersByLanguage.get(language);
        if (parser == null) {
            throw new IllegalArgumentException(
                    "Natural-language translation is only supported for LUCENE, SPL, or LOGQL");
        }
        if (prompt == null || prompt.isBlank()) {
            return NlQueryTranslateResponse.unmatched(language, "Type a description of what you're looking for.");
        }

        List<String> sourceNames = sourceRepository.findAll().stream().map(LogSource::getName).toList();
        NlQueryIntent intent = NlQueryIntentExtractor.extract(prompt, sourceNames);
        if (intent.isEmpty()) {
            return NlQueryTranslateResponse.unmatched(language,
                    "Couldn't recognize a level, known source, time range, or search text in that prompt - "
                            + "try rephrasing, or type the query directly.");
        }

        String query = buildQuery(intent, language);
        try {
            parser.parse(query);
        } catch (IllegalArgumentException e) {
            return NlQueryTranslateResponse.unmatched(language,
                    "Translated to an invalid query (" + e.getMessage() + ") - try rephrasing, or type the query directly.");
        }

        return new NlQueryTranslateResponse(true, language, query, intent.rangeMinutes(), summarize(intent));
    }

    private String buildQuery(NlQueryIntent intent, QueryLanguage language) {
        return switch (language) {
            case LUCENE -> buildLucene(intent);
            case SPL -> buildSpl(intent);
            case LOGQL -> buildLogQl(intent);
            case SIMPLE -> throw new IllegalStateException("SIMPLE is not a query-bar syntax");
        };
    }

    private String buildLucene(NlQueryIntent intent) {
        List<String> clauses = new ArrayList<>();
        if (intent.level() != null) clauses.add("level:" + intent.level());
        if (intent.source() != null) clauses.add("source:" + quoted(intent.source()));
        if (intent.freeText() != null) clauses.add(quoted(intent.freeText()));
        return clauses.isEmpty() ? "*:*" : String.join(" AND ", clauses);
    }

    private String buildSpl(NlQueryIntent intent) {
        List<String> clauses = new ArrayList<>();
        if (intent.level() != null) clauses.add("level=" + intent.level());
        if (intent.source() != null) clauses.add("source=" + quoted(intent.source()));
        if (intent.freeText() != null) clauses.add(quoted(intent.freeText()));
        return String.join(" AND ", clauses);
    }

    private String buildLogQl(NlQueryIntent intent) {
        List<String> labels = new ArrayList<>();
        if (intent.source() != null) labels.add("source=" + quoted(intent.source()));
        if (intent.level() != null) labels.add("level=" + quoted(intent.level().name()));
        String selector = "{" + String.join(", ", labels) + "}";
        return intent.freeText() == null ? selector : selector + " |= " + quoted(intent.freeText());
    }

    /** Strips characters that would break out of a quoted literal in any of the three grammars. */
    private String quoted(String value) {
        return "\"" + value.replace("\"", "").replace("\\", "") + "\"";
    }

    private String summarize(NlQueryIntent intent) {
        List<String> parts = new ArrayList<>();
        if (intent.level() != null) parts.add("level=" + intent.level());
        if (intent.source() != null) parts.add("source=" + intent.source());
        if (intent.freeText() != null) parts.add("text=\"" + intent.freeText() + "\"");
        if (intent.rangeMinutes() != null) {
            parts.add("last " + intent.rangeMinutes() + " minute" + (intent.rangeMinutes() == 1 ? "" : "s"));
        }
        return "Recognized " + String.join(", ", parts) + ".";
    }
}
