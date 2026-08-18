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
        QueryParser parser = parsersByLanguage.get(language);
        if (parser == null) {
            throw new IllegalArgumentException("Unsupported query language: " + language);
        }
        ParsedQuery parsed = parser.parse(q);

        List<QueryNode> clauses = new ArrayList<>(QueryNode.scopeClauses(source, file, rangeMinutes));
        clauses.add(parsed.filter());
        QueryNode combined = clauses.size() == 1 ? clauses.get(0) : new QueryNode.AndNode(clauses);

        Query compiled = queryCompiler.compile(combined);
        return parsed.aggregation()
                .map(stage -> executor.executeAggregation(compiled, stage, rangeMinutes))
                .orElseGet(() -> executor.execute(compiled, sortBy, sortDir, page, size));
    }
}
