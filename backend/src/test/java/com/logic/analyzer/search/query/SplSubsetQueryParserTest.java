package com.logic.analyzer.search.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SplSubsetQueryParserTest {

    private final SplSubsetQueryParser parser = new SplSubsetQueryParser();

    @Test
    void blankQueryMatchesEverything() {
        ParsedQuery parsed = parser.parse("");
        assertThat(parsed.filter()).isInstanceOf(QueryNode.MatchAllNode.class);
        assertThat(parsed.aggregation()).isEmpty();
    }

    @Test
    void orGroupsWithParentheses() {
        ParsedQuery parsed = parser.parse("(level=ERROR OR level=WARN) AND source=payments-api");

        assertThat(parsed.filter()).isInstanceOf(QueryNode.AndNode.class);
        QueryNode.AndNode and = (QueryNode.AndNode) parsed.filter();
        assertThat(and.children()).hasSize(2);
        assertThat(and.children().get(0)).isInstanceOf(QueryNode.OrNode.class);
    }

    @Test
    void mapsUnknownFieldNamesToTheDynamicFieldPrefix() {
        ParsedQuery parsed = parser.parse("service=payments");

        assertThat(parsed.filter()).isEqualTo(new QueryNode.FieldMatchNode("field.service", "payments", true));
    }

    @Test
    void keywordFieldsMapDirectlyWithoutThePrefix() {
        ParsedQuery parsed = parser.parse("source=payments-api");

        assertThat(parsed.filter()).isEqualTo(new QueryNode.FieldMatchNode("source", "payments-api", true));
    }

    @Test
    void numericComparisonTargetsTheNumSibling() {
        ParsedQuery parsed = parser.parse("status>=500");

        assertThat(parsed.filter())
                .isEqualTo(new QueryNode.NumericFieldRangeNode("field.status#num", 500.0, null, true, true));
    }

    @Test
    void statsCountByParsesIntoAnAggregationStage() {
        ParsedQuery parsed = parser.parse("level=ERROR | stats count by source");

        assertThat(parsed.aggregation()).contains(new AggregationStage.StatsCountByStage("source"));
    }

    @Test
    void statsNumericFunctionByGroupParsesIntoANumericStatsByStage() {
        ParsedQuery parsed = parser.parse("level=ERROR | stats avg(field.duration_ms) by source");

        assertThat(parsed.aggregation()).contains(new AggregationStage.NumericStatsByStage(
                "field.duration_ms", AggregationStage.NumericStatFunction.AVG, "source"));
    }

    @Test
    void statsNumericFunctionAcceptsABareFieldNameAndCaseInsensitiveFunction() {
        ParsedQuery parsed = parser.parse("| stats P95(duration_ms) by source");

        assertThat(parsed.aggregation()).contains(new AggregationStage.NumericStatsByStage(
                "field.duration_ms", AggregationStage.NumericStatFunction.P95, "source"));
    }

    @Test
    void statsNumericFunctionWithoutByHasANullGroupByField() {
        ParsedQuery parsed = parser.parse("| stats sum(field.duration_ms)");

        assertThat(parsed.aggregation()).contains(new AggregationStage.NumericStatsByStage(
                "field.duration_ms", AggregationStage.NumericStatFunction.SUM, null));
    }

    @Test
    void rejectsTwoBareTermsWithNoOperatorBetweenThem() {
        assertThatThrownBy(() -> parser.parse("foo bar"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownAggregationStages() {
        assertThatThrownBy(() -> parser.parse("level=ERROR | eval x=1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported SPL aggregation stage");
    }

    @Test
    void rejectsAComparisonWithANonNumericValue() {
        assertThatThrownBy(() -> parser.parse("status>=notanumber"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numeric");
    }

    @Test
    void rejectsAnUnterminatedQuotedString() {
        assertThatThrownBy(() -> parser.parse("\"unterminated"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAMissingClosingParenthesis() {
        assertThatThrownBy(() -> parser.parse("(level=ERROR"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
