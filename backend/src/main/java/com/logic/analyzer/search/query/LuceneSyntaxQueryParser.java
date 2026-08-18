package com.logic.analyzer.search.query;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.Query;
import org.springframework.stereotype.Component;

/**
 * Native Lucene classic query syntax - full support, no translation needed:
 * the parsed query is wrapped directly as a single {@link QueryNode.NativeLuceneNode}.
 * This is what satisfies the "KQL/Lucene" query language - Lucene's own
 * syntax already gives field:value matching, boolean operators, phrases and
 * wildcards natively. Numeric range syntax (e.g. against timestampMillis) is
 * out of scope here - the classic QueryParser has no points-field support
 * (that's a flexible-parser-only feature), and the UI's own time-range
 * control already covers that need without requiring it to be typed.
 *
 * Lucene's classic QueryParser is fully qualified throughout rather than
 * imported, since its simple name collides with this package's own
 * {@link QueryParser} interface.
 */
@Component
public class LuceneSyntaxQueryParser implements QueryParser {

    private final Analyzer analyzer;

    public LuceneSyntaxQueryParser(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    @Override
    public QueryLanguage language() {
        return QueryLanguage.LUCENE;
    }

    @Override
    public ParsedQuery parse(String queryString) {
        org.apache.lucene.queryparser.classic.QueryParser parser =
                new org.apache.lucene.queryparser.classic.QueryParser("_all", analyzer);
        try {
            String text = queryString == null || queryString.isBlank() ? "*:*" : queryString;
            Query query = parser.parse(text);
            return ParsedQuery.filterOnly(new QueryNode.NativeLuceneNode(query));
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid Lucene query: " + e.getMessage(), e);
        }
    }
}
