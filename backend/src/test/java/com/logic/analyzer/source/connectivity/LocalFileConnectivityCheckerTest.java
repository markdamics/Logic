package com.logic.analyzer.source.connectivity;

import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.SourceStatus;
import com.logic.analyzer.source.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFileConnectivityCheckerTest {

    @TempDir
    Path tempDir;

    private final LocalFileConnectivityChecker checker = new LocalFileConnectivityChecker();

    @Test
    void supportsUploadTypesAlongsideLocalTypes() {
        assertThat(checker.supports()).containsExactlyInAnyOrder(
                SourceType.LOCAL_FILE, SourceType.LOCAL_DIRECTORY, SourceType.UPLOAD_FILE, SourceType.UPLOAD_DIRECTORY);
    }

    @Test
    void uploadFileIsReachableWhenItExists() throws IOException {
        Path file = Files.writeString(tempDir.resolve("app.log"), "content");
        LogSource source = new LogSource("app-log", SourceType.UPLOAD_FILE, file.toString(), null, null, null, null);

        assertThat(checker.check(source).status()).isEqualTo(SourceStatus.REACHABLE);
    }

    @Test
    void uploadFileIsUnreachableWhenPathIsActuallyADirectory() throws IOException {
        LogSource source = new LogSource("app-log", SourceType.UPLOAD_FILE, tempDir.toString(), null, null, null, null);

        assertThat(checker.check(source).status()).isEqualTo(SourceStatus.UNREACHABLE);
    }

    @Test
    void uploadDirectoryIsReachableWhenItExists() {
        LogSource source = new LogSource("logs-dir", SourceType.UPLOAD_DIRECTORY, tempDir.toString(), null, null, null, null);

        assertThat(checker.check(source).status()).isEqualTo(SourceStatus.REACHABLE);
    }

    @Test
    void uploadDirectoryIsUnreachableWhenPathIsActuallyAFile() throws IOException {
        Path file = Files.writeString(tempDir.resolve("app.log"), "content");
        LogSource source = new LogSource("logs-dir", SourceType.UPLOAD_DIRECTORY, file.toString(), null, null, null, null);

        assertThat(checker.check(source).status()).isEqualTo(SourceStatus.UNREACHABLE);
    }
}
