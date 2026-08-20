package com.logic.analyzer.logstream;

import com.logic.analyzer.logstream.dto.LogQueryResult;
import com.logic.analyzer.search.index.SearchIndexService;
import com.logic.analyzer.search.query.LuceneQueryExecutor;
import com.logic.analyzer.search.query.QueryCompiler;
import com.logic.analyzer.search.query.QueryNode;
import org.apache.lucene.search.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Filters/sorts/paginates log entries via the embedded Lucene search index
 * rather than an in-memory scan: LogQueryParams is translated into the
 * shared QueryNode AST (the same AST every query-language parser produces),
 * compiled to a Lucene Query, and executed through the shared
 * LuceneQueryExecutor - so indexing benefits the existing simple filters,
 * not just the query-bar (SearchQueryService). The index itself is
 * populated in the background by SearchIndexService; this class only reads it.
 */
@Service
public class LogQueryService {

    private final LogIngestionService ingestionService;
    private final SearchIndexService searchIndexService;
    private final LuceneQueryExecutor executor;
    private final QueryCompiler queryCompiler;

    public LogQueryService(LogIngestionService ingestionService, SearchIndexService searchIndexService,
                            LuceneQueryExecutor executor, QueryCompiler queryCompiler) {
        this.ingestionService = ingestionService;
        this.searchIndexService = searchIndexService;
        this.executor = executor;
        this.queryCompiler = queryCompiler;
    }

    public LogQueryResult query(LogQueryParams params) {
        return executor.execute(compile(params), params.sortBy(), params.sortDir(), params.page(), params.size());
    }

    /** Just the compiled scope Query, for callers that run their own execution (e.g. AlertEvaluationService). */
    public Query compile(LogQueryParams params) {
        return queryCompiler.compile(toQueryNode(params));
    }

    /** Invalidates the interactive ingestion cache and forces an immediate reindex, so a manual Reload reflects instantly. */
    public void reload() {
        ingestionService.invalidateAll();
        searchIndexService.reindexNow();
    }

    /** Distinct, sorted file labels seen across indexed entries, optionally scoped to one source. */
    public List<String> listFiles(String source) {
        return executor.listFiles(source);
    }

    private QueryNode toQueryNode(LogQueryParams params) {
        List<QueryNode> clauses = new ArrayList<>(
                QueryNode.scopeClauses(params.source(), params.file(), params.rangeMinutes()));

        if (params.levels() != null && !params.levels().isEmpty()) {
            List<QueryNode> levelClauses = params.levels().stream()
                    .<QueryNode>map(level -> new QueryNode.FieldMatchNode("level", level.name(), true))
                    .toList();
            clauses.add(new QueryNode.OrNode(levelClauses));
        }

        if (params.search() != null && !params.search().isBlank()) {
            clauses.add(new QueryNode.TermNode(params.search().trim()));
        }

        return clauses.isEmpty() ? new QueryNode.MatchAllNode() : new QueryNode.AndNode(clauses);
    }
}
