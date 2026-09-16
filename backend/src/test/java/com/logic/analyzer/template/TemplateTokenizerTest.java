package com.logic.analyzer.template;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateTokenizerTest {

    @Test
    void masksNumbersIpsUuidsAndIdentifiersButKeepsLiteralWords() {
        List<String> tokens = TemplateTokenizer.tokenize(
                "User bob123 logged in from 10.0.0.5 session 550e8400-e29b-41d4-a716-446655440000 retries 3");

        assertThat(tokens).containsExactly(
                "User", "<ID>", "logged", "in", "from", "<IP>", "session", "<UUID>", "retries", "<NUM>");
    }

    @Test
    void masksASingleWholeTokenQuotedString() {
        assertThat(TemplateTokenizer.tokenize("status \"active\" confirmed"))
                .containsExactly("status", "<STR>", "confirmed");
    }

    @Test
    void blankInputProducesNoTokens() {
        assertThat(TemplateTokenizer.tokenize("")).isEmpty();
        assertThat(TemplateTokenizer.tokenize(null)).isEmpty();
    }

    @Test
    void splitsOnCommasSoAnUnspacedCsvRowTokenizesPerColumn() {
        List<String> tokens = TemplateTokenizer.tokenize(
                "1001,Liu,Brown,liu.brown1@example.com,BR,enterprise,2026-01-15");

        assertThat(tokens).containsExactly("<NUM>", "Liu", "Brown", "<EMAIL>", "BR", "enterprise", "<DATE>");
    }

    @Test
    void commaSpaceInOrdinaryProseIsTreatedAsOneDelimiter() {
        assertThat(TemplateTokenizer.tokenize("Payment failed for order 48291, amount 129.99"))
                .containsExactly("Payment", "failed", "for", "order", "<NUM>", "amount", "<NUM>");
    }

    @Test
    void masksEmailAddresses() {
        assertThat(TemplateTokenizer.tokenize("contact jane.doe+test@example.co.uk for help"))
                .containsExactly("contact", "<EMAIL>", "for", "help");
    }

    @Test
    void masksIsoDatesAndTimestamps() {
        assertThat(TemplateTokenizer.tokenize("expires 2026-01-15")).containsExactly("expires", "<DATE>");
        assertThat(TemplateTokenizer.tokenize("logged 2026-01-15T10:30:00Z")).containsExactly("logged", "<DATE>");
    }
}
