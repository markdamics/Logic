package com.logic.analyzer.template;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogTemplateTest {

    private static final long BUCKET_MS = LogTemplate.HISTORY_BUCKET_MILLIS;

    @Test
    void newTemplateSeedsHistoryWithItsInitialOccurrenceCount() {
        Instant now = Instant.ofEpochMilli(10 * BUCKET_MS);
        LogTemplate template = new LogTemplate("src", "hello *", 2, now, "hello world", 3);

        assertThat(template.getHistoryCounts()).containsExactly(3L);
    }

    @Test
    void anOccurrenceInTheSameBucketIncrementsTheLastEntry() {
        Instant now = Instant.ofEpochMilli(10 * BUCKET_MS);
        LogTemplate template = new LogTemplate("src", "hello *", 2, now, "hello world", 1);

        template.recordOccurrence(now.plusMillis(BUCKET_MS / 2), "hello world", 2);

        assertThat(template.getHistoryCounts()).containsExactly(3L);
    }

    @Test
    void anOccurrenceInTheNextBucketAppendsANewEntry() {
        Instant now = Instant.ofEpochMilli(10 * BUCKET_MS);
        LogTemplate template = new LogTemplate("src", "hello *", 2, now, "hello world", 1);

        template.recordOccurrence(now.plusMillis(BUCKET_MS), "hello world", 4);

        assertThat(template.getHistoryCounts()).containsExactly(1L, 4L);
    }

    @Test
    void aGapOfSeveralBucketsIsFilledWithZeros() {
        Instant now = Instant.ofEpochMilli(10 * BUCKET_MS);
        LogTemplate template = new LogTemplate("src", "hello *", 2, now, "hello world", 1);

        template.recordOccurrence(now.plusMillis(3 * BUCKET_MS), "hello world", 5);

        assertThat(template.getHistoryCounts()).containsExactly(1L, 0L, 0L, 5L);
    }

    @Test
    void historyIsCappedAtTheConfiguredLength() {
        Instant now = Instant.ofEpochMilli(10 * BUCKET_MS);
        LogTemplate template = new LogTemplate("src", "hello *", 2, now, "hello world", 1);

        for (int i = 1; i <= LogTemplate.HISTORY_LENGTH + 5; i++) {
            template.recordOccurrence(now.plusMillis((long) i * BUCKET_MS), "hello world", 1);
        }

        List<Long> history = template.getHistoryCounts();
        assertThat(history).hasSize(LogTemplate.HISTORY_LENGTH);
        assertThat(history).allSatisfy(count -> assertThat(count).isEqualTo(1L));
    }
}
