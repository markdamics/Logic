package com.logic.analyzer.search;

import com.logic.analyzer.logstream.dto.LogQueryResult;
import com.logic.analyzer.search.query.LuceneQueryExecutor;
import com.logic.analyzer.search.query.ParsedQuery;
import com.logic.analyzer.search.query.QueryCompiler;
import com.logic.analyzer.search.query.QueryLanguage;
import com.logic.analyzer.search.query.QueryNode;
import com.logic.analyzer.search.query.QueryParser;
import org.apache.lucene.search.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Backs the query-bar endpoint: dispatches a raw query string to whichever
 * QueryParser handles the requested language, ANDs the result with the same
 * source/file/time-range scoping the simple-filter UI uses
 * ({@link QueryNode#scopeClauses}), and executes it through the same
 * LuceneQueryExecutor LogQueryService uses - one AST, one compiler, one
 * executor, for both entry points.
 */
@Service
public class SearchQueryService {

    private final Map<QueryLanguage, QueryParser> parsersByLanguage;
    private final QueryCompiler queryCompiler;
    private final LuceneQueryExecutor executor;

    public SearchQueryService(List<QueryParser> parsers, QueryCompiler queryCompiler, LuceneQueryExecutor executor) {
        this.parsersByLanguage = new EnumMap<>(QueryLanguage.class);
        for (QueryParser parser : parsers) {
            parsersByLanguage.put(parser.language(), parser);
        }
        this.queryCompiler = queryCompiler;
        this.executor = executor;
    }

    public LogQueryResult query(String q, QueryLanguage language, String source, String file, long rangeMinutes,
                                 String sortBy, String sortDir, int page, int size) {
        ParsedQuery parsed = parse(q, language);
        Query compiled = compileScope(parsed.filter(), source, file, rangeMinutes);
        return parsed.aggregation()
                .map(stage -> executor.executeAggregation(compiled, stage, rangeMinutes))
                .orElseGet(() -> executor.execute(compiled, sortBy, sortDir, page, size));
    }

    /**
     * Just the compiled scope Query (parse + scope, ignoring any aggregation
     * stage the query text might include) - for callers that run their own
     * execution instead of rows/aggregation (e.g. AlertEvaluationService,
     * which builds its own CountOverTimeStage window).
     */
    public Query compile(String q, QueryLanguage language, String source, String file, long rangeMinutes) {
        return compileScope(parse(q, language).filter(), source, file, rangeMinutes);
    }

    private ParsedQuery parse(String q, QueryLanguage language) {
        QueryParser parser = parsersByLanguage.get(language);
        if (parser == null) {
            throw new IllegalArgumentException("Unsupported query language: " + language);
        }
        return parser.parse(q);
    }

    private Query compileScope(QueryNode filter, String source, String file, long rangeMinutes) {
        List<QueryNode> clauses = new ArrayList<>(QueryNode.scopeClauses(source, file, rangeMinutes));
        clauses.add(filter);
        QueryNode combined = clauses.size() == 1 ? clauses.get(0) : new QueryNode.AndNode(clauses);
        return queryCompiler.compile(combined);
    }
}
