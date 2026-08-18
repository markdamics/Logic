package com.logic.analyzer.search.query;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogQlSubsetQueryParserTest {

    private final LogQlSubsetQueryParser parser = new LogQlSubsetQueryParser();

    @Test
    void blankQueryMatchesEverything() {
        ParsedQuery parsed = parser.parse("");
        assertThat(parsed.filter()).isInstanceOf(QueryNode.MatchAllNode.class);
    }

    @Test
    void emptySelectorMatchesEverything() {
        ParsedQuery parsed = parser.parse("{}");
        assertThat(parsed.filter()).isInstanceOf(QueryNode.MatchAllNode.class);
    }

    @Test
    void singleLabelMatcherIsNotWrappedInAnAndNode() {
        ParsedQuery parsed = parser.parse("{level=\"ERROR\"}");
        assertThat(parsed.filter()).isEqualTo(new QueryNode.FieldMatchNode("level", "ERROR", true));
    }

    @Test
    void multipleLabelsAreImplicitlyAnded() {
        ParsedQuery parsed = parser.parse("{source=\"payments-api\", level=\"ERROR\"}");

        assertThat(parsed.filter()).isEqualTo(new QueryNode.AndNode(List.of(
                new QueryNode.FieldMatchNode("source", "payments-api", true),
                new QueryNode.FieldMatchNode("level", "ERROR", true))));
    }

    @Test
    void negativeAndRegexLabelMatchers() {
        assertThat(parser.parse("{level!=\"ERROR\"}").filter())
                .isEqualTo(new QueryNode.NotNode(new QueryNode.FieldMatchNode("level", "ERROR", true)));
        assertThat(parser.parse("{level=~\"ERR.*\"}").filter())
                .isEqualTo(new QueryNode.RegexNode("level", "ERR.*"));
        assertThat(parser.parse("{level!~\"ERR.*\"}").filter())
                .isEqualTo(new QueryNode.NotNode(new QueryNode.RegexNode("level", "ERR.*")));
    }

    @Test
    void chainedLineFiltersAfterTheSelector() {
        ParsedQuery parsed = parser.parse("{level=\"ERROR\"} |= \"timeout\" |~ \"conn.*refused\"");

        assertThat(parsed.filter()).isEqualTo(new QueryNode.AndNode(List.of(
                new QueryNode.FieldMatchNode("level", "ERROR", true),
                new QueryNode.PhraseNode(null, "timeout"),
                new QueryNode.RegexNode(null, "conn.*refused"))));
    }

    @Test
    void unknownLabelNamesMapToTheDynamicFieldPrefix() {
        ParsedQuery parsed = parser.parse("{service=\"payments\"}");
        assertThat(parsed.filter()).isEqualTo(new QueryNode.FieldMatchNode("field.service", "payments", true));
    }

    @Test
    void countOverTimeParsesTheBucketDurationAndInnerSelector() {
        ParsedQuery parsed = parser.parse("count_over_time({level=\"ERROR\"}[5m])");

        assertThat(parsed.aggregation()).contains(new AggregationStage.CountOverTimeStage(Duration.ofMinutes(5)));
        assertThat(parsed.filter()).isEqualTo(new QueryNode.FieldMatchNode("level", "ERROR", true));
    }

    @Test
    void rateParsesEachDurationUnit() {
        assertThat(parser.parse("rate({}[30s])").aggregation()).contains(new AggregationStage.RateStage(Duration.ofSeconds(30)));
        assertThat(parser.parse("rate({}[2h])").aggregation()).contains(new AggregationStage.RateStage(Duration.ofHours(2)));
        assertThat(parser.parse("rate({}[1d])").aggregation()).contains(new AggregationStage.RateStage(Duration.ofDays(1)));
    }

    @Test
    void rejectsAMissingClosingBrace() {
        assertThatThrownBy(() -> parser.parse("{level=\"ERROR\""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnUnterminatedQuotedValue() {
        assertThatThrownBy(() -> parser.parse("{level=\"ERROR}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnUnknownAggregationFunction() {
        assertThatThrownBy(() -> parser.parse("sum({level=\"ERROR\"}[5m])"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
