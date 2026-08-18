package com.logic.analyzer.search.query;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RegexpQuery;
import org.apache.lucene.search.TermQuery;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Compiles the shared {@link QueryNode} AST into an executable Lucene
 * {@link Query}. Text matching (TermNode/PhraseNode/analyzed FieldMatchNode)
 * goes through the classic {@link QueryParser} against the shared analyzer so
 * query-time tokenization always matches index-time tokenization, rather than
 * hand-rolling term splitting here.
 */
@Component
public class QueryCompiler {

    private final Analyzer analyzer;

    public QueryCompiler(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    public Query compile(QueryNode node) {
        try {
            return switch (node) {
                case QueryNode.MatchAllNode ignored -> new MatchAllDocsQuery();
                case QueryNode.TermNode(String text) -> parse("_all", text);
                case QueryNode.PhraseNode(String field, String phrase) -> parsePhrase(field == null ? "_all" : field, phrase);
                case QueryNode.FieldMatchNode(String field, String value, boolean exact) ->
                        exact ? new TermQuery(new Term(field, value)) : parse(field, value);
                case QueryNode.RangeNode(String field, Long lower, Long upper, boolean lowerIncl, boolean upperIncl) ->
                        LongPoint.newRangeQuery(field,
                                lower == null ? Long.MIN_VALUE : (lowerIncl ? lower : lower + 1),
                                upper == null ? Long.MAX_VALUE : (upperIncl ? upper : upper - 1));
                case QueryNode.NumericFieldRangeNode(String field, Double lower, Double upper, boolean lowerIncl, boolean upperIncl) ->
                        DoublePoint.newRangeQuery(field,
                                lower == null ? Double.NEGATIVE_INFINITY : (lowerIncl ? lower : Math.nextUp(lower)),
                                upper == null ? Double.POSITIVE_INFINITY : (upperIncl ? upper : Math.nextDown(upper)));
                case QueryNode.RegexNode(String field, String pattern) ->
                        new RegexpQuery(new Term(field == null ? "message" : field, pattern));
                case QueryNode.AndNode(List<QueryNode> children) -> combine(children, BooleanClause.Occur.MUST);
                case QueryNode.OrNode(List<QueryNode> children) -> combine(children, BooleanClause.Occur.SHOULD);
                case QueryNode.NotNode(QueryNode child) -> new BooleanQuery.Builder()
                        .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
                        .add(compile(child), BooleanClause.Occur.MUST_NOT)
                        .build();
                case QueryNode.NativeLuceneNode(Query query) -> query;
            };
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid query: " + e.getMessage(), e);
        }
    }

    private Query parse(String field, String text) throws ParseException {
        return new QueryParser(field, analyzer).parse(QueryParser.escape(text));
    }

    private Query parsePhrase(String field, String phrase) throws ParseException {
        return new QueryParser(field, analyzer).parse("\"" + QueryParser.escape(phrase) + "\"");
    }

    private Query combine(List<QueryNode> children, BooleanClause.Occur occur) {
        if (children.isEmpty()) {
            return new MatchAllDocsQuery();
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (QueryNode child : children) {
            builder.add(compile(child), occur);
        }
        return builder.build();
    }
}
