package com.logic.analyzer.template;

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

    private TemplateService service() {
        return new TemplateService(repository);
    }

    @Test
    void defaultSortOrdersByOccurrenceVolumeDescending() {
        Instant now = Instant.now();
        LogTemplate low = new LogTemplate("payments-api", "a *", 2, now, "a 1", 2);
        LogTemplate high = new LogTemplate("payments-api", "b *", 2, now, "b 1", 9);
        when(repository.findAll()).thenReturn(List.of(low, high));

        List<TemplateResponse> result = service().list(null, "volume");

        assertThat(result).extracting(TemplateResponse::occurrenceCount).containsExactly(9L, 2L);
    }

    @Test
    void recentSortOrdersByFirstSeenAtDescending() {
        Instant earlier = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant later = Instant.now();
        LogTemplate older = new LogTemplate("payments-api", "a *", 2, earlier, "a 1", 5);
        LogTemplate newer = new LogTemplate("payments-api", "b *", 2, later, "b 1", 1);
        when(repository.findAll()).thenReturn(List.of(older, newer));

        List<TemplateResponse> result = service().list(null, "recent");

        assertThat(result).extracting(TemplateResponse::templateText).containsExactly("b *", "a *");
    }

    @Test
    void aSourceFilterScopesToThatSourceOnly() {
        when(repository.findBySource("payments-api")).thenReturn(List.of(
                new LogTemplate("payments-api", "a *", 2, Instant.now(), "a 1", 1)));

        List<TemplateResponse> result = service().list("payments-api", "volume");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).source()).isEqualTo("payments-api");
    }
}
