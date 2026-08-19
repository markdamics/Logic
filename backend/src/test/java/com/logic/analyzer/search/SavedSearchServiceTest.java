package com.logic.analyzer.search;

import com.logic.analyzer.exception.SavedSearchNotFoundException;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.logstream.LogQueryParams;
import com.logic.analyzer.logstream.LogQueryService;
import com.logic.analyzer.logstream.dto.LogQueryResult;
import com.logic.analyzer.search.dto.SavedSearchCreateRequest;
import com.logic.analyzer.search.query.QueryLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SavedSearchServiceTest {

    @Mock
    private SavedSearchRepository repository;

    @Mock
    private LogQueryService logQueryService;

    @Mock
    private SearchQueryService searchQueryService;

    private SavedSearchService service() {
        return new SavedSearchService(repository, logQueryService, searchQueryService);
    }

    @Test
    void rejectsAQueryBarSaveMissingTheRawQuery() {
        SavedSearchCreateRequest request = new SavedSearchCreateRequest(
                "my search", QueryLanguage.SPL, null, null, null, null, null, 0, "time", "desc");

        assertThatThrownBy(() -> service().create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void acceptsASimpleModeSaveWithNoRawQuery() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SavedSearchCreateRequest request = new SavedSearchCreateRequest(
                "errors only", QueryLanguage.SIMPLE, null, "timeout", Set.of(LogLevel.ERROR),
                "payments-api", null, 60, "time", "desc");

        var response = service().create(request);

        assertThat(response.name()).isEqualTo("errors only");
        assertThat(response.search()).isEqualTo("timeout");
        assertThat(response.levels()).containsExactly(LogLevel.ERROR);
        assertThat(response.query()).isNull();
    }

    @Test
    void acceptsAQueryBarSaveWithARawQuery() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SavedSearchCreateRequest request = new SavedSearchCreateRequest(
                "spl errors", QueryLanguage.SPL, "level=ERROR | stats count by source", null, null,
                null, null, 0, "time", "desc");

        var response = service().create(request);

        assertThat(response.query()).isEqualTo("level=ERROR | stats count by source");
        assertThat(response.queryLanguage()).isEqualTo(QueryLanguage.SPL);
    }

    @Test
    void deleteThrowsWhenTheSavedSearchDoesNotExist() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service().delete(99L))
                .isInstanceOf(SavedSearchNotFoundException.class);
    }

    @Test
    void runThrowsWhenTheSavedSearchDoesNotExist() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().run(99L, 0, 10))
                .isInstanceOf(SavedSearchNotFoundException.class);
    }

    @Test
    void runningASimpleModeSavedSearchGoesThroughLogQueryService() {
        SavedSearch saved = new SavedSearch(
                "errors only", QueryLanguage.SIMPLE, null, "timeout", Set.of(LogLevel.ERROR),
                "payments-api", null, 60, "time", "desc");
        when(repository.findById(1L)).thenReturn(Optional.of(saved));
        LogQueryResult expected = new LogQueryResult(List.of(), 0, 10, 0, 0, null);
        when(logQueryService.query(any())).thenReturn(expected);

        var result = service().run(1L, 0, 10);

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<LogQueryParams> captor = ArgumentCaptor.forClass(LogQueryParams.class);
        verify(logQueryService).query(captor.capture());
        assertThat(captor.getValue().search()).isEqualTo("timeout");
        assertThat(captor.getValue().levels()).containsExactly(LogLevel.ERROR);
        assertThat(captor.getValue().source()).isEqualTo("payments-api");
        verify(searchQueryService, never()).query(any(), any(), any(), any(), anyLong(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void runningAQueryBarSavedSearchGoesThroughSearchQueryService() {
        SavedSearch saved = new SavedSearch(
                "spl errors", QueryLanguage.SPL, "level=ERROR", null, null, null, null, 0, "time", "desc");
        when(repository.findById(2L)).thenReturn(Optional.of(saved));
        LogQueryResult expected = new LogQueryResult(List.of(), 0, 10, 0, 0, null);
        when(searchQueryService.query(
                eq("level=ERROR"), eq(QueryLanguage.SPL), any(), any(), anyLong(), any(), any(), anyInt(), anyInt()))
                .thenReturn(expected);

        var result = service().run(2L, 0, 10);

        assertThat(result).isSameAs(expected);
        verify(logQueryService, never()).query(any());
    }
}
