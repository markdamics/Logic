package com.logic.analyzer.source;

import com.logic.analyzer.source.dto.DirectoryListing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemBrowseServiceTest {

    @TempDir
    Path tempDir;

    private final FileSystemBrowseService service = new FileSystemBrowseService();

    @Test
    void listsDirectoriesBeforeFilesEachAlphabetically() throws IOException {
        Files.createDirectory(tempDir.resolve("zdir"));
        Files.createDirectory(tempDir.resolve("adir"));
        Files.writeString(tempDir.resolve("zfile.log"), "content");
        Files.writeString(tempDir.resolve("afile.log"), "content");

        DirectoryListing listing = service.browse(tempDir.toString());

        assertThat(listing.path()).isEqualTo(tempDir.toString());
        assertThat(listing.entries())
                .extracting("name", "directory")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("adir", true),
                        org.assertj.core.groups.Tuple.tuple("zdir", true),
                        org.assertj.core.groups.Tuple.tuple("afile.log", false),
                        org.assertj.core.groups.Tuple.tuple("zfile.log", false));
    }

    @Test
    void entryPathsAreAbsolute() throws IOException {
        Path child = Files.createDirectory(tempDir.resolve("sub"));

        DirectoryListing listing = service.browse(tempDir.toString());

        assertThat(listing.entries()).extracting("path").containsExactly(child.toString());
    }

    @Test
    void parentIsSetWhenNotAtFilesystemRoot() {
        DirectoryListing listing = service.browse(tempDir.toString());

        assertThat(listing.parent()).isEqualTo(tempDir.getParent().toString());
    }

    @Test
    void blankPathDefaultsToTheUserHomeDirectory() {
        DirectoryListing listing = service.browse(null);

        assertThat(listing.path()).isEqualTo(Path.of(System.getProperty("user.home")).toString());
    }

    @Test
    void rejectsAPathThatDoesNotExist() {
        assertThatThrownBy(() -> service.browse(tempDir.resolve("missing").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void rejectsAFilePathBecauseOnlyDirectoriesCanBeBrowsed() throws IOException {
        Path file = Files.writeString(tempDir.resolve("app.log"), "content");

        assertThatThrownBy(() -> service.browse(file.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a directory");
    }
}
