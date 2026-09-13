package com.logic.analyzer.search.nlquery;

import com.logic.analyzer.logstream.LogLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NlQueryIntentExtractorTest {

    private static final List<String> SOURCES = List.of("payments-api", "auth-service");

    @Test
    void extractsLevelSourceAndRelativeHourRange() {
        NlQueryIntent intent = NlQueryIntentExtractor.extract(
                "show me errors from payments-api in the last hour", SOURCES);

        assertThat(intent.level()).isEqualTo(LogLevel.ERROR);
        assertThat(intent.source()).isEqualTo("payments-api");
        assertThat(intent.rangeMinutes()).isEqualTo(60);
        assertThat(intent.freeText()).isNull();
    }

    @Test
    void extractsWarnLevelAndSourceWithNoTimePhrase() {
        NlQueryIntent intent = NlQueryIntentExtractor.extract("warnings from auth-service", SOURCES);

        assertThat(intent.level()).isEqualTo(LogLevel.WARN);
        assertThat(intent.source()).isEqualTo("auth-service");
        assertThat(intent.rangeMinutes()).isNull();
        assertThat(intent.freeText()).isNull();
    }

    @Test
    void extractsNumericLastNDaysRange() {
        NlQueryIntent intent = NlQueryIntentExtractor.extract(
                "failed login attempts in the last 24h", SOURCES);

        assertThat(intent.rangeMinutes()).isEqualTo(24 * 60);
        assertThat(intent.level()).isNull();
        assertThat(intent.source()).isNull();
        assertThat(intent.freeText()).isEqualTo("failed login attempts");
    }

    @Test
    void extractsNumericLastNHoursAndMinutes() {
        assertThat(NlQueryIntentExtractor.extract("last 3 hours", SOURCES).rangeMinutes()).isEqualTo(180);
        assertThat(NlQueryIntentExtractor.extract("last 90 minutes", SOURCES).rangeMinutes()).isEqualTo(90);
        assertThat(NlQueryIntentExtractor.extract("last 2 days", SOURCES).rangeMinutes()).isEqualTo(2880);
    }

    @Test
    void doesNotStealALevelWordThatIsPartOfAKnownSourceName() {
        NlQueryIntent intent = NlQueryIntentExtractor.extract(
                "show me logs from info-service", List.of("info-service"));

        assertThat(intent.source()).isEqualTo("info-service");
        assertThat(intent.level()).isNull();
    }

    @Test
    void unrecognizedPromptProducesAnEmptyIntent() {
        NlQueryIntent intent = NlQueryIntentExtractor.extract("asdf qwer zxcv", List.of());

        assertThat(intent.isEmpty()).isFalse(); // free text alone still counts
        assertThat(intent.freeText()).isEqualTo("asdf qwer zxcv");
    }

    @Test
    void blankPromptProducesAnEmptyIntent() {
        NlQueryIntent intent = NlQueryIntentExtractor.extract("   ", List.of());

        assertThat(intent.isEmpty()).isTrue();
    }

    @Test
    void sinceATimeOfDayProducesAPositiveRange() {
        NlQueryIntent intent = NlQueryIntentExtractor.extract("warnings since 1am", SOURCES);

        assertThat(intent.level()).isEqualTo(LogLevel.WARN);
        assertThat(intent.rangeMinutes()).isPositive();
    }

    @Test
    void todayProducesAPositiveRange() {
        NlQueryIntent intent = NlQueryIntentExtractor.extract("errors today", SOURCES);

        assertThat(intent.level()).isEqualTo(LogLevel.ERROR);
        assertThat(intent.rangeMinutes()).isPositive();
    }
}
