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
}
