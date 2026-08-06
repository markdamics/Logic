package com.logic.analyzer.source;

import com.logic.analyzer.source.dto.LogSourceCreateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogSourceValidationTest {

    @Mock
    private LogSourceRepository repository;

    private LogSourceService service() {
        return new LogSourceService(repository, List.of());
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
}
