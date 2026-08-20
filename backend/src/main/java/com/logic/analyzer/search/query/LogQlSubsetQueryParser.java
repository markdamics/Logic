package com.logic.analyzer.search.query;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A documented SUBSET of Grafana Loki's LogQL, not full parity: a label
 * (stream) selector {@code {field="value", field2=~"regex"}}, chained line
 * filters ({@code |=}, {@code !=}, {@code |~}, {@code !~}), and one optional
 * wrapping aggregation - {@code count_over_time(...[5m])}, {@code rate(...[1m])},
 * or a numeric statistic {@code avg_over_time(field.duration_ms{...}[5m])}
 * (also min/max/sum/p50/p95/p99) - with duration units s/m/h/d.
 *
 * Hand-written recursive-descent over a small character cursor, matching
 * this codebase's existing style of hand-rolled parsing (see LogLineParser)
 * rather than pulling in a parser-generator dependency.
 *
 * Examples:
 * <pre>
 *   {source="payments-api", level="ERROR"}
 *   {level="ERROR"} |= "timeout"
 *   {source="payments-api"} |~ "conn(ection)? refused"
 *   count_over_time({level="ERROR"}[5m])
 *   rate({level="ERROR"}[1m])
 *   avg_over_time(field.duration_ms{level="ERROR"}[5m])
 *   p95_over_time(field.duration_ms{}[1m])
 * </pre>
 */
@Component
public class LogQlSubsetQueryParser implements QueryParser {

    /** Fixed schema fields a bare label name maps to directly; anything else becomes field.&lt;name&gt;. */
    private static final Set<String> KEYWORD_FIELDS = Set.of("level", "source", "file", "format");
    private static final Pattern AGGREGATION_WRAPPER =
            Pattern.compile("(?s)^(count_over_time|rate)\\s*\\((.*)\\[(\\d+)([smhd])]\\s*\\)\\s*$");
    private static final Pattern NUMERIC_AGGREGATION_WRAPPER = Pattern.compile(
            "(?is)^(avg|min|max|sum|p50|p95|p99)_over_time\\(\\s*([A-Za-z0-9_.]+)\\s*(\\{.*)\\[(\\d+)([smhd])]\\s*\\)\\s*$");

    @Override
    public QueryLanguage language() {
        return QueryLanguage.LOGQL;
    }

    @Override
    public ParsedQuery parse(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return ParsedQuery.filterOnly(new QueryNode.MatchAllNode());
        }
        String trimmed = queryString.trim();

        Matcher numericAggregation = NUMERIC_AGGREGATION_WRAPPER.matcher(trimmed);
        if (numericAggregation.matches()) {
            AggregationStage.NumericStatFunction function =
                    AggregationStage.NumericStatFunction.valueOf(numericAggregation.group(1).toUpperCase(Locale.ROOT));
            String numericField = resolveNumericStatsField(numericAggregation.group(2));
            QueryNode filter = parsePlainQuery(numericAggregation.group(3).trim());
            Duration bucket = toDuration(Long.parseLong(numericAggregation.group(4)), numericAggregation.group(5));
            AggregationStage stage = new AggregationStage.NumericStatsOverTimeStage(numericField, function, bucket);
            return new ParsedQuery(filter, Optional.of(stage));
        }

        Matcher aggregation = AGGREGATION_WRAPPER.matcher(trimmed);
        if (aggregation.matches()) {
            String function = aggregation.group(1);
            QueryNode filter = parsePlainQuery(aggregation.group(2).trim());
            Duration bucket = toDuration(Long.parseLong(aggregation.group(3)), aggregation.group(4));
            AggregationStage stage = "rate".equals(function)
                    ? new AggregationStage.RateStage(bucket)
                    : new AggregationStage.CountOverTimeStage(bucket);
            return new ParsedQuery(filter, Optional.of(stage));
        }

        return ParsedQuery.filterOnly(parsePlainQuery(trimmed));
    }

    private QueryNode parsePlainQuery(String text) {
        Cursor cursor = new Cursor(text);
        List<QueryNode> clauses = new ArrayList<>(parseSelector(cursor));
        cursor.skipWhitespace();
        while (cursor.hasMore()) {
            clauses.add(parseLineFilter(cursor));
            cursor.skipWhitespace();
        }
        if (clauses.isEmpty()) {
            return new QueryNode.MatchAllNode();
        }
        return clauses.size() == 1 ? clauses.get(0) : new QueryNode.AndNode(clauses);
    }

    // ── {field="value", field2=~"regex"} ──

    private List<QueryNode> parseSelector(Cursor cursor) {
        cursor.expect('{');
        List<QueryNode> matchers = new ArrayList<>();
        cursor.skipWhitespace();
        if (cursor.peek() == '}') {
            cursor.advance();
            return matchers; // {} - an empty selector, matches everything
        }
        while (true) {
            matchers.add(parseLabelMatcher(cursor));
            cursor.skipWhitespace();
            char c = cursor.peekOrFail();
            if (c == ',') {
                cursor.advance();
                cursor.skipWhitespace();
                continue;
            }
            if (c == '}') {
                cursor.advance();
                break;
            }
            throw new IllegalArgumentException("Expected ',' or '}' in label selector, found '" + c + "'");
        }
        return matchers;
    }

    private QueryNode parseLabelMatcher(Cursor cursor) {
        String name = cursor.readIdent();
        cursor.skipWhitespace();
        String op = cursor.readOneOf("=~", "!~", "!=", "=");
        cursor.skipWhitespace();
        String value = cursor.readQuotedString();
        String field = resolveKeywordField(name);
        return switch (op) {
            case "=" -> new QueryNode.FieldMatchNode(field, value, true);
            case "!=" -> new QueryNode.NotNode(new QueryNode.FieldMatchNode(field, value, true));
            case "=~" -> new QueryNode.RegexNode(field, value);
            case "!~" -> new QueryNode.NotNode(new QueryNode.RegexNode(field, value));
            default -> throw new IllegalStateException("Unhandled label operator: " + op);
        };
    }

    // ── |= "text"  |~ "regex"  !=  !~ ──

    private QueryNode parseLineFilter(Cursor cursor) {
        String op = cursor.readOneOf("|=", "|~", "!~", "!=");
        cursor.skipWhitespace();
        String value = cursor.readQuotedString();
        return switch (op) {
            case "|=" -> new QueryNode.PhraseNode(null, value);
            case "!=" -> new QueryNode.NotNode(new QueryNode.PhraseNode(null, value));
            case "|~" -> new QueryNode.RegexNode(null, value);
            case "!~" -> new QueryNode.NotNode(new QueryNode.RegexNode(null, value));
            default -> throw new IllegalStateException("Unhandled line filter operator: " + op);
        };
    }

    private String resolveKeywordField(String name) {
        return KEYWORD_FIELDS.contains(name) ? name : "field." + name;
    }

    /** LuceneQueryExecutor expects the full "field.&lt;name&gt;" key - accepts a bare name too, prepending "field." if missing. */
    private String resolveNumericStatsField(String name) {
        String trimmed = name.trim();
        return trimmed.startsWith("field.") ? trimmed : "field." + trimmed;
    }

    private Duration toDuration(long amount, String unit) {
        return switch (unit) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("Unsupported duration unit: " + unit);
        };
    }

    /** Fresh per parse() call - this class is a shared singleton bean, so parser position can't live on its own fields. */
    private static final class Cursor {
        private final String text;
        private int pos;

        Cursor(String text) {
            this.text = text;
        }

        boolean hasMore() {
            return pos < text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        char peekOrFail() {
            if (pos >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of query");
            }
            return text.charAt(pos);
        }

        char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        void advance() {
            pos++;
        }

        void expect(char c) {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at position " + pos);
            }
            pos++;
        }

        String readIdent() {
            skipWhitespace();
            int start = pos;
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            if (pos == start) {
                throw new IllegalArgumentException("Expected a label name at position " + start);
            }
            return text.substring(start, pos);
        }

        String readOneOf(String... options) {
            for (String option : options) {
                if (text.startsWith(option, pos)) {
                    pos += option.length();
                    return option;
                }
            }
            throw new IllegalArgumentException("Expected one of " + Arrays.toString(options) + " at position " + pos);
        }

        String readQuotedString() {
            if (pos >= text.length() || text.charAt(pos) != '"') {
                throw new IllegalArgumentException("Expected a quoted string at position " + pos);
            }
            int end = text.indexOf('"', pos + 1);
            if (end < 0) {
                throw new IllegalArgumentException("Unterminated quoted string");
            }
            String value = text.substring(pos + 1, end);
            pos = end + 1;
            return value;
        }
    }
}
