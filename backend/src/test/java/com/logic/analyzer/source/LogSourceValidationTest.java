package com.logic.analyzer.source;

import com.logic.analyzer.exception.SourceNotFoundException;
import com.logic.analyzer.logstream.LogIngestionService;
import com.logic.analyzer.search.index.SearchIndexService;
import com.logic.analyzer.source.dto.LogSourceCreateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSourceValidationTest {

    @Mock
    private LogSourceRepository repository;

    @Mock
    private LogIngestionService ingestionService;

    @Mock
    private SearchIndexService searchIndexService;

    @Mock
    private SourceUploadService uploadService;

    private LogSourceService service() {
        return new LogSourceService(repository, ingestionService, searchIndexService, uploadService, List.of());
    }

    @Test
    void rejectsSftpSourceMissingHostAndUsername() {
        LogSourceCreateRequest request = new LogSourceCreateRequest(
                "prod-web1", SourceType.SFTP, "/var/log/app.log", null, 22, null, "secret");

        assertThatThrownBy(() -> service().create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void rejectsLocalFileSourceMissingPath() {
        LogSourceCreateRequest request = new LogSourceCreateRequest(
                "app-log", SourceType.LOCAL_FILE, null, null, null, null, null);

        assertThatThrownBy(() -> service().create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    @Test
    void rejectsHttpSourceWithNonUrlPath() {
        LogSourceCreateRequest request = new LogSourceCreateRequest(
                "remote-log", SourceType.HTTP, "not-a-url", null, null, null, null);

        assertThatThrownBy(() -> service().create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("URL");
    }

    @Test
    void acceptsValidRequestsOfEachType() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service().create(new LogSourceCreateRequest(
                "app-log", SourceType.LOCAL_FILE, "/var/log/app.log", null, null, null, null)).name())
                .isEqualTo("app-log");

        assertThat(service().create(new LogSourceCreateRequest(
                "logs-dir", SourceType.LOCAL_DIRECTORY, "/var/log/myapp", null, null, null, null)).name())
                .isEqualTo("logs-dir");

        assertThat(service().create(new LogSourceCreateRequest(
                "prod-web1", SourceType.SFTP, "/var/log/nginx/access.log", "10.0.0.5", 22, "deploy", "secret")).name())
                .isEqualTo("prod-web1");

        assertThat(service().create(new LogSourceCreateRequest(
                "remote-log", SourceType.HTTP, "https://example.com/logs/app.log", null, null, null, null)).name())
                .isEqualTo("remote-log");
    }

    @Test
    void updateReplacesFieldsAndResetsStatus() {
        LogSource existing = new LogSource(
                "old-name", SourceType.SFTP, "/var/log/old.log", "10.0.0.1", 22, "olduser", "oldpass");
        existing.setStatus(SourceStatus.REACHABLE);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LogSourceCreateRequest request = new LogSourceCreateRequest(
                "new-name", SourceType.SFTP, "/var/log/new.log", "10.0.0.2", 2222, "newuser", null);

        var response = service().update(1L, request);

        assertThat(response.name()).isEqualTo("new-name");
        assertThat(response.host()).isEqualTo("10.0.0.2");
        assertThat(response.port()).isEqualTo(2222);
        assertThat(response.username()).isEqualTo("newuser");
        assertThat(response.status()).isEqualTo(SourceStatus.UNVERIFIED);
        assertThat(existing.getPassword()).isEqualTo("oldpass");
    }

    @Test
    void newSourcesAreEnabledByDefault() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().create(new LogSourceCreateRequest(
                "app-log", SourceType.LOCAL_FILE, "/var/log/app.log", null, null, null, null));

        assertThat(response.enabled()).isTrue();
    }

    @Test
    void setEnabledTogglesTheSourceAndPersists() {
        LogSource existing = new LogSource(
                "app-log", SourceType.LOCAL_FILE, "/var/log/app.log", null, null, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var disabled = service().setEnabled(1L, false);
        assertThat(disabled.enabled()).isFalse();
        assertThat(existing.isEnabled()).isFalse();

        var reenabled = service().setEnabled(1L, true);
        assertThat(reenabled.enabled()).isTrue();
    }

    @Test
    void newSourcesAreNotLiveByDefault() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().create(new LogSourceCreateRequest(
                "app-log", SourceType.LOCAL_FILE, "/var/log/app.log", null, null, null, null));

        assertThat(response.live()).isFalse();
    }

    @Test
    void setLiveTogglesTheSourceAndPersists() {
        LogSource existing = new LogSource(
                "app-log", SourceType.LOCAL_FILE, "/var/log/app.log", null, null, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var live = service().setLive(1L, true);
        assertThat(live.live()).isTrue();
        assertThat(existing.isLive()).isTrue();

        var notLive = service().setLive(1L, false);
        assertThat(notLive.live()).isFalse();
    }

    @Test
    void setLiveRejectsUploadSources() {
        LogSource uploadFile = new LogSource(
                "upload-log", SourceType.UPLOAD_FILE, "/data/uploads/abc/app.log", null, null, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(uploadFile));

        assertThatThrownBy(() -> service().setLive(1L, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Live mode");

        verify(repository, never()).save(any());
    }

    @Test
    void listAllSurfacesChangedFilesPerSource() {
        LogSource stale = new LogSource("stale-source", SourceType.LOCAL_FILE, "/var/log/stale.log", null, null, null, null);
        LogSource fresh = new LogSource("fresh-source", SourceType.LOCAL_FILE, "/var/log/fresh.log", null, null, null, null);
        when(repository.findAll()).thenReturn(List.of(stale, fresh));
        when(ingestionService.changedFiles(stale)).thenReturn(List.of("stale.log"));
        when(ingestionService.changedFiles(fresh)).thenReturn(List.of());

        var responses = service().listAll();

        assertThat(responses).extracting(r -> r.name(), r -> r.changedFiles())
                .containsExactly(
                        tuple("stale-source", List.of("stale.log")),
                        tuple("fresh-source", List.of())
                );
    }

    @Test
    void deletePurgesTheSourceFromTheSearchIndex() {
        LogSource existing = new LogSource(
                "app-log", SourceType.LOCAL_FILE, "/var/log/app.log", null, null, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        service().delete(1L);

        verify(repository).deleteById(1L);
        verify(searchIndexService).purgeSource(existing);
    }

    @Test
    void deleteThrowsAndNeverPurgesWhenTheSourceDoesNotExist() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(99L))
                .isInstanceOf(SourceNotFoundException.class);

        verify(searchIndexService, never()).purgeSource(any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void deleteCleansUpUploadStorageForUploadSources() {
        LogSource existing = new LogSource(
                "upload-log", SourceType.UPLOAD_FILE, "/data/uploads/abc/app.log", null, null, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        service().delete(1L);

        verify(uploadService).deleteStorage(existing);
    }
}
