package com.logic.analyzer.template;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    @Mock
    private LogTemplateRepository repository;

    @Mock
    private LogSourceRepository sourceRepository;

    private TemplateService service() {
        return new TemplateService(repository, sourceRepository);
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
}
