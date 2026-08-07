package com.logic.analyzer.logstream;

import com.logic.analyzer.logstream.dto.LogQueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogQueryServiceTest {

    @Mock
    private LogIngestionService ingestionService;

    private LogQueryService service() {
        return new LogQueryService(ingestionService);
    }

    private List<LogEntry> sample() {
        Instant now = Instant.now();
        return List.of(
                new LogEntry(1, now.minusSeconds(10), LogLevel.ERROR, "source-a", "app.log", "boom failure"),
                new LogEntry(2, now.minusSeconds(20), LogLevel.INFO, "source-b", "web.log", "all good"),
                new LogEntry(3, now.minusSeconds(30), LogLevel.WARN, "source-a", "app.log", "careful now"),
                new LogEntry(4, now.minus(Duration.ofDays(10)), LogLevel.ERROR, "source-a", "app.log", "ancient failure")
        );
    }

    @Test
    void filtersBySearchLevelSourceAndRange() {
        when(ingestionService.collectEntries()).thenReturn(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                "failure", Set.of(LogLevel.ERROR), "source-a", null, 60, "time", "desc", 0, 10));

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().get(0).id()).isEqualTo(1);
    }

    @Test
    void excludesEntriesOutsideTheRequestedTimeRange() {
        when(ingestionService.collectEntries()).thenReturn(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                null, Set.of(), null, null, 60, "time", "desc", 0, 10));

        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.content()).extracting(LogEntry::id).doesNotContain(4L);
    }

    @Test
    void treatsNonPositiveRangeMinutesAsAllTime() {
        when(ingestionService.collectEntries()).thenReturn(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                null, Set.of(), null, null, 0, "time", "desc", 0, 10));

        assertThat(result.totalElements()).isEqualTo(4);
        assertThat(result.content()).extracting(LogEntry::id).contains(4L);
    }

    @Test
    void sortsByLevelSeverity() {
        when(ingestionService.collectEntries()).thenReturn(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                null, Set.of(), null, null, 60, "level", "asc", 0, 10));

        assertThat(result.content()).extracting(LogEntry::level)
                .containsExactly(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO);
    }

    @Test
    void sortsBySourceThenRespectsDirection() {
        when(ingestionService.collectEntries()).thenReturn(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                null, Set.of(), null, null, 60, "source", "asc", 0, 10));

        assertThat(result.content()).extracting(LogEntry::source)
                .containsExactly("source-a", "source-a", "source-b");
    }

    @Test
    void paginatesResults() {
        when(ingestionService.collectEntries()).thenReturn(sample());

        LogQueryResult page0 = service().query(new LogQueryParams(null, Set.of(), null, null, 60, "time", "desc", 0, 2));
        LogQueryResult page1 = service().query(new LogQueryParams(null, Set.of(), null, null, 60, "time", "desc", 1, 2));

        assertThat(page0.content()).hasSize(2);
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page1.content()).hasSize(1);
    }

    @Test
    void filtersByFile() {
        when(ingestionService.collectEntries()).thenReturn(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                null, Set.of(), null, "web.log", 60, "time", "desc", 0, 10));

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().get(0).id()).isEqualTo(2);
    }

    @Test
    void searchTermMatchesAgainstFileLabelToo() {
        when(ingestionService.collectEntries()).thenReturn(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                "web.log", Set.of(), null, null, 60, "time", "desc", 0, 10));

        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.content().get(0).id()).isEqualTo(2);
    }

    @Test
    void sortsByFile() {
        when(ingestionService.collectEntries()).thenReturn(sample());

        LogQueryResult result = service().query(new LogQueryParams(
                null, Set.of(), null, null, 60, "file", "asc", 0, 10));

        assertThat(result.content()).extracting(LogEntry::id)
                .containsExactly(1L, 3L, 2L);
    }

    @Test
    void listFilesReturnsDistinctSortedNonBlankFileLabels() {
        when(ingestionService.collectEntries()).thenReturn(sample());

        List<String> files = service().listFiles(null);

        assertThat(files).containsExactly("app.log", "web.log");
    }

    @Test
    void listFilesScopedToSource() {
        when(ingestionService.collectEntries()).thenReturn(sample());

        List<String> files = service().listFiles("source-b");

        assertThat(files).containsExactly("web.log");
    }
}
