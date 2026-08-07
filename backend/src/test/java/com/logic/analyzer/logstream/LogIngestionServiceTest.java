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
        assertThat(entries).allMatch(e -> e.file().equals("app.log"));
    }

    @Test
    void tagsEntriesWithFileNameWhenReadingADirectory() throws IOException {
        Files.writeString(tempDir.resolve("a.log"), "2026-08-06 08:00:00,000 [INFO] X - from A\n");
        Files.writeString(tempDir.resolve("b.log"), "2026-08-06 08:00:00,000 [INFO] X - from B\n");
        LogSource source = new LogSource("dir-source", SourceType.LOCAL_DIRECTORY, tempDir.toString(), null, null, null, null);
        when(repository.findAll()).thenReturn(List.of(source));

        List<LogEntry> entries = service().collectEntries();

        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(LogEntry::file)
                .containsExactlyInAnyOrder("a.log", "b.log");
        assertThat(entries).extracting(LogEntry::message)
                .containsExactlyInAnyOrder("X - from A", "X - from B");
    }

    @Test
    void skipsDisabledSourcesEntirely() throws IOException {
        Path file = tempDir.resolve("app.log");
        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - should not appear\n");
        LogSource source = new LogSource("paused", SourceType.LOCAL_FILE, file.toString(), null, null, null, null);
        source.setEnabled(false);
        when(repository.findAll()).thenReturn(List.of(source));

        List<LogEntry> entries = service().collectEntries();

        assertThat(entries).isEmpty();
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
        assertThat(entries.get(0).file()).isNull();
    }

    @Test
    void nonLiveSourcesAreFrozenAfterTheFirstReadUntilExplicitlyReloaded() throws IOException {
        Path file = tempDir.resolve("app.log");
        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - first version\n");

        // The cache keys on source id, which only exists once a source is persisted -
        // mock it here rather than relying on the plain constructor (which leaves id
        // null) so this test actually exercises the cache instead of skipping it.
        // isLive() defaults to false on a Mockito mock, matching a non-live source.
        LogSource source = mock(LogSource.class);
        when(source.getId()).thenReturn(1L);
        when(source.getName()).thenReturn("cached");
        when(source.getType()).thenReturn(SourceType.LOCAL_FILE);
        when(source.getPath()).thenReturn(file.toString());
        when(source.isEnabled()).thenReturn(true);
        when(repository.findAll()).thenReturn(List.of(source));

        LogIngestionService service = service();
        List<LogEntry> first = service.collectEntries();

        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - second version\n");
        List<LogEntry> second = service.collectEntries();

        assertThat(first.get(0).message()).contains("first version");
        assertThat(first.get(0).file()).isEqualTo("app.log");
        assertThat(second.get(0).message()).contains("first version"); // frozen, no automatic refresh

        service.invalidateAll();
        List<LogEntry> afterReload = service.collectEntries();

        assertThat(afterReload.get(0).message()).contains("second version");
    }

    @Test
    void liveSourcesReReadAfterTheShortCacheWindowExpires() throws IOException, InterruptedException {
        Path file = tempDir.resolve("app.log");
        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - first version\n");

        LogSource source = mock(LogSource.class);
        when(source.getId()).thenReturn(1L);
        when(source.getName()).thenReturn("live-source");
        when(source.getType()).thenReturn(SourceType.LOCAL_FILE);
        when(source.getPath()).thenReturn(file.toString());
        when(source.isEnabled()).thenReturn(true);
        when(source.isLive()).thenReturn(true);
        when(repository.findAll()).thenReturn(List.of(source));

        LogIngestionService service = service();
        List<LogEntry> first = service.collectEntries();
        assertThat(first.get(0).message()).contains("first version");

        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - second version\n");
        Thread.sleep(2100); // longer than the 2s live cache TTL

        List<LogEntry> second = service.collectEntries();
        assertThat(second.get(0).message()).contains("second version");
    }

    @Test
    void changedFilesIsEmptyBeforeTheSourceHasEverBeenRead() {
        LogSource source = mock(LogSource.class);
        when(source.getId()).thenReturn(1L);
        when(source.isEnabled()).thenReturn(true);

        assertThat(service().changedFiles(source)).isEmpty();
    }

    @Test
    void changedFilesIsAlwaysEmptyForLiveSources() throws IOException {
        Path file = tempDir.resolve("app.log");
        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - v1\n");

        LogSource source = mock(LogSource.class);
        when(source.getId()).thenReturn(1L);
        when(source.getName()).thenReturn("live-source");
        when(source.getType()).thenReturn(SourceType.LOCAL_FILE);
        when(source.getPath()).thenReturn(file.toString());
        when(source.isEnabled()).thenReturn(true);
        when(source.isLive()).thenReturn(true);
        when(repository.findAll()).thenReturn(List.of(source));

        LogIngestionService service = service();
        service.collectEntries();
        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - v2 with a lot more content\n");

        assertThat(service.changedFiles(source)).isEmpty();
    }

    @Test
    void changedFilesIsEmptyWhenNothingHasChangedSinceTheLastRead() throws IOException {
        Path file = tempDir.resolve("app.log");
        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - v1\n");

        LogSource source = mock(LogSource.class);
        when(source.getId()).thenReturn(1L);
        when(source.getName()).thenReturn("watched");
        when(source.getType()).thenReturn(SourceType.LOCAL_FILE);
        when(source.getPath()).thenReturn(file.toString());
        when(source.isEnabled()).thenReturn(true);
        when(repository.findAll()).thenReturn(List.of(source));

        LogIngestionService service = service();
        service.collectEntries();

        assertThat(service.changedFiles(source)).isEmpty();
    }

    @Test
    void changedFilesNamesTheModifiedFileForALocalFileSource() throws IOException {
        Path file = tempDir.resolve("app.log");
        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - v1\n");

        LogSource source = mock(LogSource.class);
        when(source.getId()).thenReturn(1L);
        when(source.getName()).thenReturn("watched");
        when(source.getType()).thenReturn(SourceType.LOCAL_FILE);
        when(source.getPath()).thenReturn(file.toString());
        when(source.isEnabled()).thenReturn(true);
        when(repository.findAll()).thenReturn(List.of(source));

        LogIngestionService service = service();
        service.collectEntries(); // freezes the source and records a fingerprint

        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - v1\nsome appended content\n");

        assertThat(service.changedFiles(source)).containsExactly("app.log");
    }

    @Test
    void changedFilesNamesOnlyTheActuallyModifiedFileInADirectory() throws IOException {
        Files.writeString(tempDir.resolve("a.log"), "2026-08-06 08:00:00,000 [INFO] X - from A\n");
        Files.writeString(tempDir.resolve("b.log"), "2026-08-06 08:00:00,000 [INFO] X - from B\n");

        LogSource source = mock(LogSource.class);
        when(source.getId()).thenReturn(1L);
        when(source.getName()).thenReturn("dir-source");
        when(source.getType()).thenReturn(SourceType.LOCAL_DIRECTORY);
        when(source.getPath()).thenReturn(tempDir.toString());
        when(source.isEnabled()).thenReturn(true);
        when(repository.findAll()).thenReturn(List.of(source));

        LogIngestionService service = service();
        service.collectEntries(); // freezes the source and records a fingerprint per file

        Files.writeString(tempDir.resolve("b.log"), "2026-08-06 08:00:00,000 [INFO] X - from B\nmore content\n");

        assertThat(service.changedFiles(source)).containsExactly("b.log");
    }

    @Test
    void invalidateAllResetsTheChangedFilesSignalUntilTheNextRead() throws IOException {
        Path file = tempDir.resolve("app.log");
        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - v1\n");

        LogSource source = mock(LogSource.class);
        when(source.getId()).thenReturn(1L);
        when(source.getName()).thenReturn("watched");
        when(source.getType()).thenReturn(SourceType.LOCAL_FILE);
        when(source.getPath()).thenReturn(file.toString());
        when(source.isEnabled()).thenReturn(true);
        when(repository.findAll()).thenReturn(List.of(source));

        LogIngestionService service = service();
        service.collectEntries();
        Files.writeString(file, "2026-08-06 08:00:00,000 [INFO] X - v1\nsome appended content\n");
        assertThat(service.changedFiles(source)).containsExactly("app.log");

        service.invalidateAll();

        assertThat(service.changedFiles(source)).isEmpty();
    }
}
