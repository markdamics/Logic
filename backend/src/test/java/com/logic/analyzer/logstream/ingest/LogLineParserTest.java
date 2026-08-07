package com.logic.analyzer.logstream.ingest;

import com.logic.analyzer.logstream.LogLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogLineParserTest {

    @Test
    void detectsBracketedLevelsAndAppLogTimestamps() {
        List<LogLineParser.ParsedLine> parsed = LogLineParser.parse(List.of(
                "2026-08-06 08:00:41,000 [ERROR] AuthService - boom"));

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).level()).isEqualTo(LogLevel.ERROR);
        assertThat(parsed.get(0).timestamp().toString()).startsWith("2026-08-06T08:00:41");
        assertThat(parsed.get(0).message()).isEqualTo("AuthService - boom");
    }

    @Test
    void stripsAppLogTimestampAndBracketedLevelFromTheMessage() {
        List<LogLineParser.ParsedLine> parsed = LogLineParser.parse(List.of(
                "2026-08-07 12:11:44,281 [INFO] PaymentGateway - Request processed successfully in 2015ms"));

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).level()).isEqualTo(LogLevel.INFO);
        assertThat(parsed.get(0).message())
                .isEqualTo("PaymentGateway - Request processed successfully in 2015ms");
    }

    @Test
    void mergesIndentedContinuationLinesIntoThePreviousEntry() {
        List<LogLineParser.ParsedLine> parsed = LogLineParser.parse(List.of(
                "2026-08-06 08:00:41,000 [ERROR] AuthService - boom",
                "\tat com.example.Foo.bar(Foo.java:1)",
                "\tat com.example.Foo.baz(Foo.java:2)",
                "2026-08-06 08:00:42,000 [INFO] AuthService - recovered"));

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).message()).startsWith("AuthService - boom");
        assertThat(parsed.get(0).message()).contains("Foo.bar").contains("Foo.baz");
        assertThat(parsed.get(1).level()).isEqualTo(LogLevel.INFO);
    }

    @Test
    void derivesLevelFromHttpStatusWhenNoExplicitLevelIsPresent() {
        List<LogLineParser.ParsedLine> parsed = LogLineParser.parse(List.of(
                "203.0.113.1 - - [06/Aug/2026:08:00:00 +0000] \"GET /x HTTP/1.1\" 500 123 \"-\" \"curl\""));

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).level()).isEqualTo(LogLevel.ERROR);
        assertThat(parsed.get(0).timestamp().toString()).startsWith("2026-08-06T08:00:00");
        assertThat(parsed.get(0).message())
                .isEqualTo("203.0.113.1 - - \"GET /x HTTP/1.1\" 500 123 \"-\" \"curl\"");
    }

    @Test
    void treats4xxAccessLogStatusAsWarn() {
        List<LogLineParser.ParsedLine> parsed = LogLineParser.parse(List.of(
                "203.0.113.1 - - [06/Aug/2026:08:00:00 +0000] \"GET /x HTTP/1.1\" 404 123 \"-\" \"curl\""));

        assertThat(parsed.get(0).level()).isEqualTo(LogLevel.WARN);
    }

    @Test
    void defaultsToInfoWhenNothingMatches() {
        List<LogLineParser.ParsedLine> parsed = LogLineParser.parse(List.of("just some unstructured text"));

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).level()).isEqualTo(LogLevel.INFO);
    }
}
