package com.logic.analyzer.logstream;

import com.logic.analyzer.logstream.ingest.HttpTailSource;
import com.logic.analyzer.logstream.ingest.LocalTailSource;
import com.logic.analyzer.logstream.ingest.LogLineParser;
import com.logic.analyzer.logstream.ingest.LogTailReader;
import com.logic.analyzer.logstream.ingest.SftpTailSource;
import com.logic.analyzer.logstream.ingest.TailSource;
import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.LogSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Reads real content from each configured source - tailing the last portion of
 * large files/remote paths rather than loading them whole - and parses it into
 * LogEntry objects for LogQueryService to filter/sort/paginate. A short-lived
 * per-source cache absorbs the burst of requests a single UI interaction can
 * trigger (sort clicks, pagination, debounced search) without re-reading disk
 * or a remote host on every one of them.
 *
 * Known simplifications: directories are read non-recursively and capped to
 * the most recently modified files; there's no incremental/streaming tail
 * (each read re-fetches the trailing window), and SFTP opens a fresh
 * connection per read rather than pooling one.
 */
@Service
public class LogIngestionService {

    private static final Logger log = LoggerFactory.getLogger(LogIngestionService.class);

    private static final int MAX_TAIL_BYTES = 512 * 1024;
    private static final int MAX_LINES_PER_FILE = 3000;
    private static final int MAX_FILES_PER_DIRECTORY = 20;
    private static final Duration CACHE_TTL = Duration.ofSeconds(3);
    private static final int DEFAULT_SFTP_PORT = 22;

    private final LogSourceRepository sourceRepository;
    private final Map<Long, CachedEntries> cache = new ConcurrentHashMap<>();

    public LogIngestionService(LogSourceRepository sourceRepository) {
        this.sourceRepository = sourceRepository;
    }

    public List<LogEntry> collectEntries() {
        List<LogSource> sources = sourceRepository.findAll();
        List<LogEntry> all = new ArrayList<>();
        long id = 1;
        for (LogSource source : sources) {
            for (LogEntry entry : entriesFor(source)) {
                all.add(new LogEntry(id++, entry.timestamp(), entry.level(), entry.source(), entry.message()));
            }
        }
        return all;
    }

    private List<LogEntry> entriesFor(LogSource source) {
        Long id = source.getId();
        if (id == null) {
            // Not yet persisted (or a detached instance) - nothing to key the cache on.
            return readSource(source);
        }
        CachedEntries cached = cache.get(id);
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return cached.entries();
        }
        List<LogEntry> entries = readSource(source);
        cache.put(id, new CachedEntries(entries, Instant.now()));
        return entries;
    }

    private List<LogEntry> readSource(LogSource source) {
        try {
            return switch (source.getType()) {
                case LOCAL_FILE -> readLocalFile(Path.of(source.getPath()), source.getName());
                case LOCAL_DIRECTORY -> readLocalDirectory(Path.of(source.getPath()), source.getName());
                case SFTP -> readRemoteTail(sftpTailSource(source), source.getName());
                case HTTP -> readRemoteTail(new HttpTailSource(URI.create(source.getPath())), source.getName());
            };
        } catch (Exception e) {
            log.warn("Failed to read log source {} ('{}'): {}", source.getId(), source.getName(), e.getMessage());
            return List.of(errorEntry(source.getName(), e.getMessage()));
        }
    }

    private TailSource sftpTailSource(LogSource source) {
        int port = source.getPort() != null ? source.getPort() : DEFAULT_SFTP_PORT;
        return new SftpTailSource(source.getHost(), port, source.getUsername(), source.getPassword(), source.getPath());
    }

    private List<LogEntry> readLocalFile(Path path, String sourceName) throws IOException {
        if (!Files.isRegularFile(path)) {
            return List.of(errorEntry(sourceName, "not a regular file: " + path));
        }
        List<String> lines = LogTailReader.readLastLines(new LocalTailSource(path), MAX_TAIL_BYTES, MAX_LINES_PER_FILE);
        return toEntries(lines, sourceName, null);
    }

    private List<LogEntry> readLocalDirectory(Path dir, String sourceName) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of(errorEntry(sourceName, "not a directory: " + dir));
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(this::lastModifiedSafe).reversed())
                    .limit(MAX_FILES_PER_DIRECTORY)
                    .toList();
        }

        List<LogEntry> entries = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = LogTailReader.readLastLines(new LocalTailSource(file), MAX_TAIL_BYTES, MAX_LINES_PER_FILE);
            entries.addAll(toEntries(lines, sourceName, file.getFileName().toString()));
        }
        return entries;
    }

    private List<LogEntry> readRemoteTail(TailSource tailSource, String sourceName) throws IOException {
        List<String> lines = LogTailReader.readLastLines(tailSource, MAX_TAIL_BYTES, MAX_LINES_PER_FILE);
        return toEntries(lines, sourceName, null);
    }

    private List<LogEntry> toEntries(List<String> lines, String sourceName, String fileLabel) {
        List<LogEntry> entries = new ArrayList<>();
        for (LogLineParser.ParsedLine parsed : LogLineParser.parse(lines)) {
            String message = fileLabel == null ? parsed.message() : "[" + fileLabel + "] " + parsed.message();
            entries.add(new LogEntry(0, parsed.timestamp(), parsed.level(), sourceName, message));
        }
        return entries;
    }

    private Instant lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private LogEntry errorEntry(String sourceName, String reason) {
        return new LogEntry(0, Instant.now(), LogLevel.ERROR, sourceName, "Failed to read log source: " + reason);
    }

    private record CachedEntries(List<LogEntry> entries, Instant fetchedAt) {
    }
}
