package com.logic.analyzer.template;

import com.logic.analyzer.exception.TemplateNotFoundException;
import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.logstream.LogQueryService;
import com.logic.analyzer.logstream.dto.LogQueryResult;
import com.logic.analyzer.search.index.SearchIndexService;
import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.LogSourceRepository;
import com.logic.analyzer.source.SourceType;
import com.logic.analyzer.template.dto.TemplateResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    @Mock
    private LogTemplateRepository repository;

    @Mock
    private LogSourceRepository sourceRepository;

    @Mock
    private TemplateMiningService miningService;

    @Mock
    private LogQueryService logQueryService;

    @Mock
    private SearchIndexService searchIndexService;

    private TemplateService service() {
        return new TemplateService(repository, sourceRepository, miningService, logQueryService, searchIndexService);
    }

    private LogSource miningEnabledSource(String name) {
        LogSource source = new LogSource(name, SourceType.LOCAL_FILE, "/var/log/app.log", null, null, null, null);
        source.setPatternMiningEnabled(true);
        return source;
    }

    @Test
    void defaultSortOrdersByOccurrenceVolumeDescending() {
        Instant now = Instant.now();
        LogTemplate low = new LogTemplate("payments-api", "app.log", "a *", 2, now, "a 1", 2);
        LogTemplate high = new LogTemplate("payments-api", "app.log", "b *", 2, now, "b 1", 9);
        when(repository.findAll()).thenReturn(List.of(low, high));
        when(sourceRepository.findAll()).thenReturn(List.of(miningEnabledSource("payments-api")));

        List<TemplateResponse> result = service().list(null, null, "volume");

        assertThat(result).extracting(TemplateResponse::occurrenceCount).containsExactly(9L, 2L);
    }

    @Test
    void recentSortOrdersByFirstSeenAtDescending() {
        Instant earlier = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant later = Instant.now();
        LogTemplate older = new LogTemplate("payments-api", "app.log", "a *", 2, earlier, "a 1", 5);
        LogTemplate newer = new LogTemplate("payments-api", "app.log", "b *", 2, later, "b 1", 1);
        when(repository.findAll()).thenReturn(List.of(older, newer));
        when(sourceRepository.findAll()).thenReturn(List.of(miningEnabledSource("payments-api")));

        List<TemplateResponse> result = service().list(null, null, "recent");

        assertThat(result).extracting(TemplateResponse::templateText).containsExactly("b *", "a *");
    }

    @Test
    void aSourceFilterScopesToThatSourceOnly() {
        when(repository.findBySource("payments-api")).thenReturn(List.of(
                new LogTemplate("payments-api", "app.log", "a *", 2, Instant.now(), "a 1", 1)));
        when(sourceRepository.findAll()).thenReturn(List.of(miningEnabledSource("payments-api")));

        List<TemplateResponse> result = service().list("payments-api", null, "volume");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).source()).isEqualTo("payments-api");
    }

    @Test
    void aSourceAndFileFilterScopesToThatFileOnly() {
        when(repository.findBySourceAndFile("payments-api", "app.log")).thenReturn(List.of(
                new LogTemplate("payments-api", "app.log", "a *", 2, Instant.now(), "a 1", 1)));
        when(sourceRepository.findAll()).thenReturn(List.of(miningEnabledSource("payments-api")));

        List<TemplateResponse> result = service().list("payments-api", "app.log", "volume");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).file()).isEqualTo("app.log");
    }

    @Test
    void aFileFilterWithoutASourceStillFiltersByFile() {
        // Mirrors LogQueryService: source and file compose independently rather than file
        // only taking effect once a source is also picked.
        when(repository.findByFile("app.log")).thenReturn(List.of(
                new LogTemplate("payments-api", "app.log", "a *", 2, Instant.now(), "a 1", 1)));
        when(sourceRepository.findAll()).thenReturn(List.of(miningEnabledSource("payments-api")));

        List<TemplateResponse> result = service().list(null, "app.log", "volume");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).file()).isEqualTo("app.log");
    }

    @Test
    void templatesFromASourceWithMiningCurrentlyDisabledAreHiddenEvenIfTheRowsStillExist() {
        // Simulates a row mined before the source's pattern-mining toggle was turned off
        // (or from before the toggle existed at all) - the row is still in the table, but
        // the source no longer has mining enabled.
        LogSource disabledSource = new LogSource("legacy-source", SourceType.LOCAL_FILE, "/var/log/app.log", null, null, null, null);
        disabledSource.setPatternMiningEnabled(false);
        when(repository.findAll()).thenReturn(List.of(
                new LogTemplate("legacy-source", "app.log", "stale *", 2, Instant.now(), "stale 1", 500)));
        when(sourceRepository.findAll()).thenReturn(List.of(disabledSource));

        List<TemplateResponse> result = service().list(null, null, "volume");

        assertThat(result).isEmpty();
    }

    @Test
    void templatesFromADeletedSourceAreHiddenSinceThereIsNoLongerAMatchingEnabledSource() {
        when(repository.findAll()).thenReturn(List.of(
                new LogTemplate("gone-source", "app.log", "stale *", 2, Instant.now(), "stale 1", 500)));
        when(sourceRepository.findAll()).thenReturn(List.of());

        List<TemplateResponse> result = service().list(null, null, "volume");

        assertThat(result).isEmpty();
    }

    @Test
    void templatesWithNoFileOnRecordAreHiddenAsPermanentlyOrphanedLegacyRows() {
        // A row mined before per-file scoping existed (file column added later, nullable for
        // exactly this reason) - it can never be matched again since mining always supplies a
        // real file, so it's frozen and shouldn't be surfaced as an active pattern.
        when(repository.findAll()).thenReturn(List.of(
                new LogTemplate("payments-api", null, "legacy *", 2, Instant.now(), "legacy 1", 500)));
        when(sourceRepository.findAll()).thenReturn(List.of(miningEnabledSource("payments-api")));

        List<TemplateResponse> result = service().list(null, null, "volume");

        assertThat(result).isEmpty();
    }

    @Test
    void deleteRemovesTheRowAndInvalidatesTheMiningCache() {
        when(repository.existsById(7L)).thenReturn(true);

        service().delete(7L);

        verify(repository).deleteById(7L);
        verify(miningService).invalidateCache();
    }

    @Test
    void deleteThrowsWhenTheTemplateDoesNotExist() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service().delete(99L))
                .isInstanceOf(TemplateNotFoundException.class);

        verify(repository, never()).deleteById(any());
    }

    @Test
    void splitThrowsWhenTheTemplateDoesNotExist() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().split(99L))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    @Test
    void splitThrowsWhenNothingIsCurrentlyIndexedForTheTemplate() {
        LogTemplate target = new LogTemplate("payments-api", "app.log", "User * request completed *", 5, Instant.now(), "sample", 2);
        when(repository.findById(7L)).thenReturn(Optional.of(target));
        when(logQueryService.query(any())).thenReturn(new LogQueryResult(List.of(), 0, 5000, 0, 0, null));

        assertThatThrownBy(() -> service().split(7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scrolled out");

        verify(repository, never()).deleteById(any());
    }

    @Test
    void splitThrowsWhenReclusteringFindsOnlyOneGroup() {
        LogTemplate target = new LogTemplate("payments-api", "app.log", "User * request completed *", 5, Instant.now(), "sample", 2);
        when(repository.findById(7L)).thenReturn(Optional.of(target));
        LogEntry entry = new LogEntry(1, Instant.now(), LogLevel.INFO, "payments-api", "app.log", "User bob request completed fast");
        when(logQueryService.query(any())).thenReturn(new LogQueryResult(List.of(entry), 0, 5000, 1, 1, null));
        when(miningService.recluster(anyCollection(), eq(0.85))).thenReturn(List.of(
                new TemplateMiningService.ReclusterGroup("User bob request completed fast", 5, List.of("User bob request completed fast"))));

        assertThatThrownBy(() -> service().split(7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing to split");

        verify(repository, never()).deleteById(any());
    }

    @Test
    void splitReplacesTheTemplateWithSeveralAndForcesAReindex() {
        LogTemplate target = new LogTemplate("payments-api", "app.log", "User * request completed *", 5, Instant.now(), "sample", 2);
        when(repository.findById(7L)).thenReturn(Optional.of(target));

        Instant t1 = Instant.now().minusSeconds(20);
        Instant t2 = Instant.now().minusSeconds(10);
        LogEntry entryA = new LogEntry(1, t1, LogLevel.INFO, "payments-api", "app.log", "User bob request completed fast");
        LogEntry entryB = new LogEntry(2, t2, LogLevel.INFO, "payments-api", "app.log", "User carol request completed slow");
        when(logQueryService.query(any())).thenReturn(new LogQueryResult(List.of(entryA, entryB), 0, 5000, 2, 1, null));
        when(miningService.recluster(anyCollection(), eq(0.85))).thenReturn(List.of(
                new TemplateMiningService.ReclusterGroup("User bob request completed fast", 5, List.of("User bob request completed fast")),
                new TemplateMiningService.ReclusterGroup("User carol request completed slow", 5, List.of("User carol request completed slow"))));

        LogSource source = new LogSource("payments-api", SourceType.LOCAL_FILE, "/var/log/app.log", null, null, null, null);
        when(sourceRepository.findFirstByName("payments-api")).thenReturn(Optional.of(source));
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<TemplateResponse> result = service().split(7L);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(TemplateResponse::occurrenceCount).containsExactly(1L, 1L);
        assertThat(result).extracting(TemplateResponse::templateText)
                .containsExactlyInAnyOrder("User bob request completed fast", "User carol request completed slow");
        assertThat(result).extracting(TemplateResponse::splitFromTemplateText)
                .containsExactly("User * request completed *", "User * request completed *");
        verify(repository).deleteById(7L);
        verify(miningService).invalidateCache();
        verify(searchIndexService).forceReindexSource(source);
    }

    @Test
    void splitThrowsWhenTheSourceNoLongerExists() {
        LogTemplate target = new LogTemplate("payments-api", "app.log", "User * request completed *", 5, Instant.now(), "sample", 2);
        when(repository.findById(7L)).thenReturn(Optional.of(target));
        LogEntry entryA = new LogEntry(1, Instant.now(), LogLevel.INFO, "payments-api", "app.log", "User bob request completed fast");
        LogEntry entryB = new LogEntry(2, Instant.now(), LogLevel.INFO, "payments-api", "app.log", "User carol request completed slow");
        when(logQueryService.query(any())).thenReturn(new LogQueryResult(List.of(entryA, entryB), 0, 5000, 2, 1, null));
        when(miningService.recluster(anyCollection(), eq(0.85))).thenReturn(List.of(
                new TemplateMiningService.ReclusterGroup("User bob request completed fast", 5, List.of("User bob request completed fast")),
                new TemplateMiningService.ReclusterGroup("User carol request completed slow", 5, List.of("User carol request completed slow"))));
        when(sourceRepository.findFirstByName("payments-api")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().split(7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer exists");

        verify(repository, never()).deleteById(any());
    }
}
