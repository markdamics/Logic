package com.logic.analyzer.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceUploadServiceTest {

    @TempDir
    Path tempDir;

    private SourceUploadService service() throws IOException {
        return new SourceUploadService(tempDir.resolve("uploads").toString());
    }

    @Test
    void storesASingleUploadFileAndReturnsItsPath() throws IOException {
        MultipartFile file = new MockMultipartFile("files", "app.log", "text/plain", "hello".getBytes());

        String path = service().store(SourceType.UPLOAD_FILE, new MultipartFile[] { file });

        Path written = Path.of(path);
        assertThat(written.getFileName().toString()).isEqualTo("app.log");
        assertThat(Files.readString(written)).isEqualTo("hello");
    }

    @Test
    void rejectsUploadFileWithMoreThanOneFile() throws IOException {
        MultipartFile a = new MockMultipartFile("files", "a.log", "text/plain", "a".getBytes());
        MultipartFile b = new MockMultipartFile("files", "b.log", "text/plain", "b".getBytes());

        assertThatThrownBy(() -> service().store(SourceType.UPLOAD_FILE, new MultipartFile[] { a, b }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Exactly one file");
    }

    @Test
    void storesADirectoryUploadPreservingRelativeStructure() throws IOException {
        MultipartFile root = new MockMultipartFile("files", "app.log", "text/plain", "root".getBytes());
        MultipartFile nested = new MockMultipartFile("files", "2024/nested.log", "text/plain", "nested".getBytes());

        String path = service().store(SourceType.UPLOAD_DIRECTORY, new MultipartFile[] { root, nested });

        Path dir = Path.of(path);
        assertThat(Files.readString(dir.resolve("app.log"))).isEqualTo("root");
        assertThat(Files.readString(dir.resolve("2024").resolve("nested.log"))).isEqualTo("nested");
    }

    @Test
    void rejectsPathTraversalInAnUploadedFileName() throws IOException {
        MultipartFile malicious = new MockMultipartFile("files", "../../evil.log", "text/plain", "x".getBytes());

        assertThatThrownBy(() -> service().store(SourceType.UPLOAD_DIRECTORY, new MultipartFile[] { malicious }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyFileList() throws IOException {
        assertThatThrownBy(() -> service().store(SourceType.UPLOAD_FILE, new MultipartFile[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one file");
    }

    @Test
    void deleteStorageRemovesTheFileAndItsStorageDirectoryForUploadFile() throws IOException {
        SourceUploadService service = service();
        MultipartFile file = new MockMultipartFile("files", "app.log", "text/plain", "hello".getBytes());
        String path = service.store(SourceType.UPLOAD_FILE, new MultipartFile[] { file });
        Path storageDir = Path.of(path).getParent();
        assertThat(Files.exists(storageDir)).isTrue();

        LogSource source = new LogSource("app-log", SourceType.UPLOAD_FILE, path, null, null, null, null);
        service.deleteStorage(source);

        assertThat(Files.exists(storageDir)).isFalse();
    }

    @Test
    void deleteStorageRemovesTheDirectoryForUploadDirectory() throws IOException {
        SourceUploadService service = service();
        MultipartFile file = new MockMultipartFile("files", "app.log", "text/plain", "hello".getBytes());
        String path = service.store(SourceType.UPLOAD_DIRECTORY, new MultipartFile[] { file });

        LogSource source = new LogSource("logs-dir", SourceType.UPLOAD_DIRECTORY, path, null, null, null, null);
        service.deleteStorage(source);

        assertThat(Files.exists(Path.of(path))).isFalse();
    }

    @Test
    void deleteStorageIsANoOpForNonUploadSources() throws IOException {
        LogSource source = new LogSource("app-log", SourceType.LOCAL_FILE, "/var/log/app.log", null, null, null, null);

        assertThatCode(() -> service().deleteStorage(source)).doesNotThrowAnyException();
    }
}
