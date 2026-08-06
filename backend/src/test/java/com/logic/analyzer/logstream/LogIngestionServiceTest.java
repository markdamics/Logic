package com.logic.analyzer.logstream;

import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.LogSourceRepository;
import com.logic.analyzer.source.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogIngestionServiceTest {

    @Mock
    private LogSourceRepository repository;

    @TempDir
    Path tempDir;

    private LogIngestionService service() {
        return new LogIngestionService(repository);
    }

    @Test
    void parsesRealLinesFromALocalFile() throws IOException {
        Path file = tempDir.resolve("app.log");
        Files.writeString(file, """
                2026-08-06 08:00:41,000 [INFO] AuthService - Scheduled job 'cleanup' completed
                2026-08-06 08:00:50,000 [ERROR] AuthService - Unhandled exception
                \tat com.example.AuthService.validate(AuthService.java:42)
                """);
        LogSource source = new LogSource("auth-file", SourceType.LOCAL_FILE, file.toString(), null, null, null, null);
        when(repository.findAll()).thenReturn(List.of(source));

        List<LogEntry> entries = service().collectEntries();

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).level()).isEqualTo(LogLevel.INFO);
        assertThat(entries.get(1).level()).isEqualTo(LogLevel.ERROR);
        assertThat(entries.get(1).message()).contains("AuthService.validate");
        assertThat(entries).allMatch(e -> e.source().equals("auth-file"));
    }

    @Test
    void tagsEntriesWithFileNameWhenReadingADirectory() throws IOException {
        Files.writeString(tempDir.resolve("a.log"), "2026-08-06 08:00:00,000 [INFO] X - from A\n");
        Files.writeString(tempDir.resolve("b.log"), "2026-08-06 08:00:00,000 [INFO] X - from B\n");
        LogSource source = new LogSource("dir-source", SourceType.LOCAL_DIRECTORY, tempDir.toString(), null, null, null, null);
        when(repository.findAll()).thenReturn(List.of(source));

        List<LogEntry> entries = service().collectEntries();

        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(LogEntry::message)
                .anyMatch(m -> m.startsWith("[a.log]"));
        assertThat(entries).extracting(LogEntry::message)
                .anyMatch(m -> m.startsWith("[b.log]"));
    }

    @Test
    void producesAnErrorEntryWhenThePathDoesNotExist() {
        LogSource source = new LogSource(
                "missing", SourceType.LOCAL_FILE, tempDir.resolve("nope.log").toString(), null, null, null, null);
        when(repository.findAll()).thenReturn(List.of(source));

        List<LogEntry> entries = service().collectEntries();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).level()).isEqualTo(LogLevel.ERROR);
        assertThat(entries.get(0).message()).contains("Failed to read log source");
    }

    @Test
    void cachesEntriesBrieflyInsteadOfRereadingOnEveryCall() throws IOException {
        Path file = tempDir.resolve("app.log");
        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - first version\n");

        // The cache keys on source id, which only exists once a source is persisted -
        // mock it here rather than relying on the plain constructor (which leaves id
        // null) so this test actually exercises the cache instead of skipping it.
        LogSource source = mock(LogSource.class);
        when(source.getId()).thenReturn(1L);
        when(source.getName()).thenReturn("cached");
        when(source.getType()).thenReturn(SourceType.LOCAL_FILE);
        when(source.getPath()).thenReturn(file.toString());
        when(repository.findAll()).thenReturn(List.of(source));

        LogIngestionService service = service();
        List<LogEntry> first = service.collectEntries();

        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - second version\n");
        List<LogEntry> second = service.collectEntries();

        assertThat(first.get(0).message()).contains("first version");
        assertThat(second.get(0).message()).contains("first version"); // still within the cache TTL
    }
}
