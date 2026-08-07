package com.logic.analyzer.dashboard;

import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.logstream.LogIngestionService;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.LogSourceRepository;
import com.logic.analyzer.source.SourceStatus;
import com.logic.analyzer.source.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private LogSourceRepository sourceRepository;

    @Mock
    private LogIngestionService ingestionService;

    private DashboardService service() {
        return new DashboardService(sourceRepository, ingestionService);
    }

    private LogSource source(String name, SourceStatus status) {
        LogSource source = new LogSource(name, SourceType.LOCAL_FILE, "/var/log/" + name + ".log", null, null, null, null);
        source.setStatus(status);
        return source;
    }

    @Test
    void countsSourcesByStatus() {
        when(sourceRepository.findAll()).thenReturn(List.of(
                source("source-a", SourceStatus.REACHABLE),
                source("source-b", SourceStatus.UNREACHABLE),
                source("source-c", SourceStatus.UNVERIFIED)
        ));
        when(ingestionService.collectEntries()).thenReturn(List.of());

        DashboardSummary summary = service().summarize();

        assertThat(summary.totalSources()).isEqualTo(3);
        assertThat(summary.reachableSources()).isEqualTo(1);
        assertThat(summary.unreachableSources()).isEqualTo(1);
        assertThat(summary.unverifiedSources()).isEqualTo(1);
    }

    @Test
    void countsEnabledAndDisabledSources() {
        LogSource disabled = source("paused", SourceStatus.REACHABLE);
        disabled.setEnabled(false);
        when(sourceRepository.findAll()).thenReturn(List.of(
                source("source-a", SourceStatus.REACHABLE),
                disabled
        ));
        when(ingestionService.collectEntries()).thenReturn(List.of());

        DashboardSummary summary = service().summarize();

        assertThat(summary.enabledSources()).isEqualTo(1);
        assertThat(summary.disabledSources()).isEqualTo(1);
        assertThat(summary.sourceActivity()).extracting(SourceActivity::enabled)
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    void sourceActivityReflectsWhetherEachSourceIsLive() {
        LogSource live = source("live-source", SourceStatus.REACHABLE);
        live.setLive(true);
        when(sourceRepository.findAll()).thenReturn(List.of(
                source("static-source", SourceStatus.REACHABLE),
                live
        ));
        when(ingestionService.collectEntries()).thenReturn(List.of());

        DashboardSummary summary = service().summarize();

        assertThat(summary.sourceActivity()).extracting(SourceActivity::live)
                .containsExactlyInAnyOrder(true, false);
    }

    @Test
    void countsEntriesErrorsAndWarningsWithinTheLast24Hours() {
        Instant now = Instant.now();
        when(sourceRepository.findAll()).thenReturn(List.of(source("source-a", SourceStatus.REACHABLE)));
        when(ingestionService.collectEntries()).thenReturn(List.of(
                new LogEntry(1, now.minusSeconds(10), LogLevel.ERROR, "source-a", "a.log", "boom"),
                new LogEntry(2, now.minusSeconds(20), LogLevel.WARN, "source-a", "a.log", "careful"),
                new LogEntry(3, now.minusSeconds(30), LogLevel.INFO, "source-a", "a.log", "fine"),
                new LogEntry(4, now.minus(Duration.ofDays(2)), LogLevel.ERROR, "source-a", "a.log", "old boom")
        ));

        DashboardSummary summary = service().summarize();

        assertThat(summary.entriesLast24h()).isEqualTo(3);
        assertThat(summary.errorsLast24h()).isEqualTo(1);
        assertThat(summary.warningsLast24h()).isEqualTo(1);
    }

    @Test
    void buildsPerSourceActivityForEveryConfiguredSourceEvenWhenQuiet() {
        Instant now = Instant.now();
        when(sourceRepository.findAll()).thenReturn(List.of(
                source("noisy", SourceStatus.REACHABLE),
                source("quiet", SourceStatus.REACHABLE)
        ));
        when(ingestionService.collectEntries()).thenReturn(List.of(
                new LogEntry(1, now.minusSeconds(10), LogLevel.ERROR, "noisy", "a.log", "boom"),
                new LogEntry(2, now.minusSeconds(20), LogLevel.INFO, "noisy", "a.log", "fine")
        ));

        DashboardSummary summary = service().summarize();

        assertThat(summary.sourceActivity()).extracting(SourceActivity::source)
                .containsExactly("noisy", "quiet");
        assertThat(summary.sourceActivity().get(0).entriesLast24h()).isEqualTo(2);
        assertThat(summary.sourceActivity().get(0).errorsLast24h()).isEqualTo(1);
        assertThat(summary.sourceActivity().get(1).entriesLast24h()).isEqualTo(0);
    }

    @Test
    void buildsPerFileErrorActivityGroupedBySourceAndFile() {
        Instant now = Instant.now();
        when(sourceRepository.findAll()).thenReturn(List.of(source("source-a", SourceStatus.REACHABLE)));
        when(ingestionService.collectEntries()).thenReturn(List.of(
                new LogEntry(1, now.minusSeconds(10), LogLevel.ERROR, "source-a", "noisy.log", "boom"),
                new LogEntry(2, now.minusSeconds(20), LogLevel.ERROR, "source-a", "noisy.log", "boom again"),
                new LogEntry(3, now.minusSeconds(30), LogLevel.INFO, "source-a", "quiet.log", "fine"),
                new LogEntry(4, now.minusSeconds(40), LogLevel.ERROR, "source-a", null, "no file label")
        ));

        DashboardSummary summary = service().summarize();

        assertThat(summary.fileActivity()).extracting(FileActivity::file)
                .containsExactlyInAnyOrder("noisy.log", "quiet.log");
        FileActivity noisy = summary.fileActivity().stream()
                .filter(f -> f.file().equals("noisy.log")).findFirst().orElseThrow();
        assertThat(noisy.source()).isEqualTo("source-a");
        assertThat(noisy.entriesLast24h()).isEqualTo(2);
        assertThat(noisy.errorsLast24h()).isEqualTo(2);
    }

    @Test
    void recentIssuesAreTheNewestErrorsAndWarningsRegardlessOfTimeWindow() {
        Instant now = Instant.now();
        when(sourceRepository.findAll()).thenReturn(List.of(source("source-a", SourceStatus.REACHABLE)));
        when(ingestionService.collectEntries()).thenReturn(List.of(
                new LogEntry(1, now.minus(Duration.ofDays(5)), LogLevel.ERROR, "source-a", "a.log", "old boom"),
                new LogEntry(2, now.minusSeconds(10), LogLevel.INFO, "source-a", "a.log", "fine"),
                new LogEntry(3, now.minusSeconds(5), LogLevel.WARN, "source-a", "a.log", "careful")
        ));

        DashboardSummary summary = service().summarize();

        assertThat(summary.recentIssues()).extracting(LogEntry::id).containsExactly(3L, 1L);
    }
}
