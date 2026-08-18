package com.logic.analyzer.search.query;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A documented SUBSET of Splunk SPL, not full parity: bare terms, quoted
 * phrases, {@code field=value}/{@code field!=value}, numeric comparisons
 * ({@code >,>=,<,<=}) against extracted structured fields, explicit
 * {@code AND}/{@code OR}/{@code NOT} (uppercase; two positive clauses must be
 * joined with AND/OR - no implicit AND-by-juxtaposition, the clearest
 * departure from real SPL - but a bare NOT may still follow directly, e.g.
 * {@code "text" NOT source=x}), parenthesized grouping, and one optional
 * trailing aggregation stage: {@code | stats count by <field>}.
 *
 * Hand-written recursive-descent over a small hand-rolled tokenizer, matching
 * this codebase's existing style of hand-rolled parsing (see LogLineParser)
 * rather than pulling in a parser-generator dependency.
 *
 * Examples:
 * <pre>
 *   level=ERROR AND source=payments-api
 *   "connection refused" NOT source=test-runner
 *   status&gt;=500 | stats count by source
 * </pre>
 */
@Component
public class SplSubsetQueryParser implements QueryParser {

    /** Fixed schema fields a bare name maps to directly; anything else becomes field.&lt;name&gt;. */
    private static final Set<String> KEYWORD_FIELDS = Set.of("level", "source", "file", "format");
    /** Checked longest-first so ">=" doesn't get misread as ">" followed by a stray "=". */
    private static final String[] COMPARISON_OPS = {">=", "<=", "!=", "=", ">", "<"};
    private static final Pattern STATS_COUNT_BY = Pattern.compile("(?i)^stats\\s+count\\s+by\\s+(\\S+)$");

    @Override
    public QueryLanguage language() {
        return QueryLanguage.SPL;
    }

    @Override
    public ParsedQuery parse(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return ParsedQuery.filterOnly(new QueryNode.MatchAllNode());
        }

        int pipeIndex = indexOfTopLevelPipe(queryString);
        String filterPart = pipeIndex < 0 ? queryString : queryString.substring(0, pipeIndex);
        String aggregationPart = pipeIndex < 0 ? null : queryString.substring(pipeIndex + 1);

        List<String> tokens = tokenize(filterPart);
        QueryNode filter;
        if (tokens.isEmpty()) {
            filter = new QueryNode.MatchAllNode();
        } else {
            TokenCursor cursor = new TokenCursor(tokens);
            filter = parseOr(cursor);
            if (cursor.hasMore()) {
                throw new IllegalArgumentException(
                        "Unexpected '" + cursor.peek() + "' - clauses must be joined with AND/OR");
            }
        }

        return new ParsedQuery(filter, parseAggregation(aggregationPart));
    }

    // ── boolean expression grammar: or := and (OR and)* ; and := not (AND not)* ; not := NOT not | primary ──

    private QueryNode parseOr(TokenCursor cursor) {
        QueryNode left = parseAnd(cursor);
        List<QueryNode> nodes = null;
        while ("OR".equals(cursor.peek())) {
            cursor.advance();
            if (nodes == null) {
                nodes = new ArrayList<>(List.of(left));
            }
            nodes.add(parseAnd(cursor));
        }
        return nodes == null ? left : new QueryNode.OrNode(nodes);
    }

    private QueryNode parseAnd(TokenCursor cursor) {
        QueryNode left = parseNot(cursor);
        List<QueryNode> nodes = null;
        // A bare "NOT" is the one clause allowed to follow another without an
        // explicit AND/OR first (e.g. `"text" NOT source=x`, matching real
        // SPL) - it's unambiguous, unlike two arbitrary terms in a row.
        while ("AND".equals(cursor.peek()) || "NOT".equals(cursor.peek())) {
            if ("AND".equals(cursor.peek())) {
                cursor.advance();
            }
            if (nodes == null) {
                nodes = new ArrayList<>(List.of(left));
            }
            nodes.add(parseNot(cursor));
        }
        return nodes == null ? left : new QueryNode.AndNode(nodes);
    }

    private QueryNode parseNot(TokenCursor cursor) {
        if ("NOT".equals(cursor.peek())) {
            cursor.advance();
            return new QueryNode.NotNode(parseNot(cursor));
        }
        return parsePrimary(cursor);
    }

    private QueryNode parsePrimary(TokenCursor cursor) {
        String token = cursor.peek();
        if (token == null) {
            throw new IllegalArgumentException("Unexpected end of query");
        }
        if ("(".equals(token)) {
            cursor.advance();
            QueryNode inner = parseOr(cursor);
            if (!")".equals(cursor.peek())) {
                throw new IllegalArgumentException("Missing closing ')'");
            }
            cursor.advance();
            return inner;
        }
        if (")".equals(token)) {
            throw new IllegalArgumentException("Unexpected ')'");
        }
        cursor.advance();
        return classify(token);
    }

    // ── token classification ──

    private QueryNode classify(String run) {
        if (isQuoted(run)) {
            return new QueryNode.PhraseNode(null, unquote(run));
        }
        for (String op : COMPARISON_OPS) {
            int idx = run.indexOf(op);
            if (idx > 0) {
                String field = run.substring(0, idx);
                String rawValue = run.substring(idx + op.length());
                if (rawValue.isEmpty()) {
                    throw new IllegalArgumentException("Missing value after '" + op + "' in '" + run + "'");
                }
                return comparisonNode(field, op, rawValue);
            }
        }
        return new QueryNode.TermNode(run);
    }

    private QueryNode comparisonNode(String field, String op, String rawValue) {
        String value = isQuoted(rawValue) ? unquote(rawValue) : rawValue;
        return switch (op) {
            case "=" -> new QueryNode.FieldMatchNode(resolveKeywordField(field), value, true);
            case "!=" -> new QueryNode.NotNode(new QueryNode.FieldMatchNode(resolveKeywordField(field), value, true));
            case ">" -> new QueryNode.NumericFieldRangeNode(resolveNumericField(field), parseNumber(field, value), null, false, true);
            case ">=" -> new QueryNode.NumericFieldRangeNode(resolveNumericField(field), parseNumber(field, value), null, true, true);
            case "<" -> new QueryNode.NumericFieldRangeNode(resolveNumericField(field), null, parseNumber(field, value), true, false);
            case "<=" -> new QueryNode.NumericFieldRangeNode(resolveNumericField(field), null, parseNumber(field, value), true, true);
            default -> throw new IllegalStateException("Unhandled comparison operator: " + op);
        };
    }

    private double parseNumber(String field, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "'" + field + "' comparison requires a numeric value, got '" + value + "'");
        }
    }

    private String resolveKeywordField(String name) {
        return KEYWORD_FIELDS.contains(name) ? name : "field." + name;
    }

    private String resolveNumericField(String name) {
        return "field." + name + "#num";
    }

    // ── aggregation suffix: "stats count by <field>" ──

    private Optional<AggregationStage> parseAggregation(String aggregationPart) {
        if (aggregationPart == null || aggregationPart.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = STATS_COUNT_BY.matcher(aggregationPart.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported SPL aggregation stage: '" + aggregationPart.trim()
                    + "' - only '| stats count by <field>' is supported");
        }
        return Optional.of(new AggregationStage.StatsCountByStage(resolveKeywordField(matcher.group(1))));
    }

    // ── tokenizer ──

    /** First '|' not inside a quoted string, or -1 if there is none. */
    private int indexOfTopLevelPipe(String query) {
        boolean inQuotes = false;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '|' && !inQuotes) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Splits on whitespace, keeping parens as their own tokens and quoted
     * spans (standalone, like "connection refused", or embedded in a
     * comparison like status="in progress") intact as part of whichever
     * token they're in.
     */
    private List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(' || c == ')') {
                tokens.add(String.valueOf(c));
                i++;
            } else if (c == '"') {
                int end = requireClosingQuote(input, i);
                tokens.add(input.substring(i, end + 1));
                i = end + 1;
            } else {
                int start = i;
                while (i < n && !Character.isWhitespace(input.charAt(i)) && input.charAt(i) != '(' && input.charAt(i) != ')') {
                    if (input.charAt(i) == '"') {
                        i = requireClosingQuote(input, i) + 1;
                    } else {
                        i++;
                    }
                }
                tokens.add(input.substring(start, i));
            }
        }
        return tokens;
    }

    private int requireClosingQuote(String input, int openingQuoteIndex) {
        int end = input.indexOf('"', openingQuoteIndex + 1);
        if (end < 0) {
            throw new IllegalArgumentException("Unterminated quoted string in query");
        }
        return end;
    }

    private boolean isQuoted(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"");
    }

    private String unquote(String s) {
        return s.substring(1, s.length() - 1);
    }

    /** Fresh per parse() call - this class is a shared singleton bean, so parser position can't live on its own fields. */
    private static final class TokenCursor {
        private final List<String> tokens;
        private int pos;

        TokenCursor(List<String> tokens) {
            this.tokens = tokens;
        }

        boolean hasMore() {
            return pos < tokens.size();
        }

        String peek() {
            return pos < tokens.size() ? tokens.get(pos) : null;
        }

        void advance() {
            pos++;
        }
    }
}
