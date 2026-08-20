package com.logic.analyzer.source;

import com.logic.analyzer.source.dto.DirectoryEntry;
import com.logic.analyzer.source.dto.DirectoryListing;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lists directory contents on the server's local filesystem, so the Sources
 * dialog can browse to a LOCAL_FILE/LOCAL_DIRECTORY path instead of the user
 * typing an absolute path from memory. This exposes nothing a determined
 * admin couldn't already do - the app already trusts whoever can reach this
 * API to name any server-side path directly (see LocalFileConnectivityChecker),
 * source paths aren't scoped to a sandboxed root.
 */
@Service
public class FileSystemBrowseService {

    public DirectoryListing browse(String rawPath) {
        Path path = resolve(rawPath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Path does not exist: " + path);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Path is not a directory: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("Path is not readable: " + path);
        }

        List<DirectoryEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path entry : stream) {
                try {
                    entries.add(new DirectoryEntry(
                            entry.getFileName().toString(),
                            entry.toAbsolutePath().normalize().toString(),
                            Files.isDirectory(entry)));
                } catch (RuntimeException ignored) {
                    // Stat failed for this one entry (broken symlink, permission edge
                    // case) - skip it rather than failing the whole listing.
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read directory: " + path, e);
        }

        entries.sort(Comparator.comparing(DirectoryEntry::directory, Comparator.reverseOrder())
                .thenComparing(e -> e.name().toLowerCase()));

        Path parent = path.getParent();
        return new DirectoryListing(path.toString(), parent == null ? null : parent.toString(), entries);
    }

    private Path resolve(String rawPath) {
        String trimmed = rawPath == null ? "" : rawPath.trim();
        Path path = trimmed.isEmpty() ? Path.of(System.getProperty("user.home")) : Path.of(trimmed);
        return path.toAbsolutePath().normalize();
    }
}
