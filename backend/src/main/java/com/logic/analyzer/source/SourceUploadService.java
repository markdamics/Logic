package com.logic.analyzer.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Comparator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Writes browser-uploaded files to disk so UPLOAD_FILE/UPLOAD_DIRECTORY sources can be read
 * by the same local-file ingestion/connectivity pipeline as LOCAL_FILE/LOCAL_DIRECTORY - once
 * stored, an upload is just an ordinary file (or directory of files) on the server's disk.
 */
@Service
public class SourceUploadService {

    private static final Logger log = LoggerFactory.getLogger(SourceUploadService.class);

    private final Path uploadsRoot;

    public SourceUploadService(@Value("${app.uploads.dir:./data/uploads}") String uploadsDir) throws IOException {
        this.uploadsRoot = Path.of(uploadsDir).toAbsolutePath().normalize();
        Files.createDirectories(uploadsRoot);
    }

    /**
     * Writes the uploaded files under a new UUID-named storage directory and returns the path
     * to persist on the LogSource: the single file's path for UPLOAD_FILE, or the storage
     * directory's path for UPLOAD_DIRECTORY.
     */
    public String store(SourceType type, MultipartFile[] files) {
        if (type != SourceType.UPLOAD_FILE && type != SourceType.UPLOAD_DIRECTORY) {
            throw new IllegalArgumentException("Unsupported upload source type: " + type);
        }
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("At least one file is required");
        }
        if (type == SourceType.UPLOAD_FILE && files.length != 1) {
            throw new IllegalArgumentException("Exactly one file is required for an uploaded file source");
        }

        Path sourceDir = uploadsRoot.resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(sourceDir);
            Path singleFile = writeFile(sourceDir, files[0]);
            for (int i = 1; i < files.length; i++) {
                writeFile(sourceDir, files[i]);
            }
            String path = (type == SourceType.UPLOAD_FILE ? singleFile : sourceDir).toString();
            log.info("Stored {} upload ({} file(s)) at {}", type, files.length, path);
            return path;
        } catch (Exception e) {
            deleteRecursively(sourceDir);
            throw new IllegalArgumentException("Failed to store uploaded file(s): " + e.getMessage(), e);
        }
    }

    private Path writeFile(Path sourceDir, MultipartFile file) throws IOException {
        String relativePath = sanitizeRelativePath(file.getOriginalFilename());
        Path target = sourceDir.resolve(relativePath).normalize();
        if (!target.startsWith(sourceDir)) {
            throw new IllegalArgumentException("Invalid file path in upload: " + file.getOriginalFilename());
        }
        Files.createDirectories(target.getParent());
        file.transferTo(target);
        return target;
    }

    private String sanitizeRelativePath(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("Uploaded file is missing a name");
        }
        String normalized = originalFilename.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("Uploaded file name must be relative: " + originalFilename);
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("Uploaded file name may not contain '..': " + originalFilename);
            }
        }
        return normalized;
    }

    /** No-op for non-upload source types. */
    public void deleteStorage(LogSource source) {
        if (source.getType() != SourceType.UPLOAD_FILE && source.getType() != SourceType.UPLOAD_DIRECTORY) {
            return;
        }
        if (source.getPath() == null) {
            return;
        }
        Path stored = Path.of(source.getPath());
        Path target = source.getType() == SourceType.UPLOAD_FILE ? stored.getParent() : stored;
        if (target == null || !target.startsWith(uploadsRoot)) {
            log.warn("Refusing to delete upload storage outside of uploads root: {}", target);
            return;
        }
        deleteRecursively(target);
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    log.warn("Failed to delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to walk {} for deletion: {}", dir, e.getMessage());
        }
    }
}
