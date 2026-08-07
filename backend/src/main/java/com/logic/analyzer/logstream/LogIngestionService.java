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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Reads real content from each configured source - tailing the last portion of
 * large files/remote paths rather than loading them whole - and parses it into
 * LogEntry objects for LogQueryService to filter/sort/paginate. A per-source
 * cache absorbs the burst of requests a single UI interaction can trigger
 * (sort clicks, pagination, debounced search) without re-reading disk or a
 * remote host on every one of them.
 *
 * Sources marked "live" are re-read on a short TTL, so the Log Stream and
 * Dashboard keep updating on their own as new lines arrive. Non-live sources
 * are read once and then frozen indefinitely - the cached entries are
 * returned forever - until something calls {@link #invalidateAll()} (wired
 * to the frontend's Reload action), keeping them as fixed, deliberate
 * snapshots instead of re-reading disk/network on every request.
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
    private static final Duration LIVE_CACHE_TTL = Duration.ofSeconds(2);
    private static final Duration PROBE_TTL = Duration.ofSeconds(5);
    private static final int DEFAULT_SFTP_PORT = 22;

    private final LogSourceRepository sourceRepository;
    private final Map<Long, CachedEntries> cache = new ConcurrentHashMap<>();
    private final Map<Long, ProbeResult> probeCache = new ConcurrentHashMap<>();

    public LogIngestionService(LogSourceRepository sourceRepository) {
        this.sourceRepository = sourceRepository;
    }

    public List<LogEntry> collectEntries() {
        List<LogSource> sources = sourceRepository.findAll();
        List<LogEntry> all = new ArrayList<>();
        long id = 1;
        for (LogSource source : sources) {
            if (!source.isEnabled()) {
                continue;
            }
            for (LogEntry entry : entriesFor(source)) {
                all.add(new LogEntry(id++, entry.timestamp(), entry.level(), entry.source(), entry.file(), entry.message()));
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
        if (cached != null) {
            if (!source.isLive()) {
                // Non-live sources are a fixed snapshot once read - only invalidateAll() refreshes them.
                return cached.entries();
            }
            if (Duration.between(cached.fetchedAt(), Instant.now()).compareTo(LIVE_CACHE_TTL) < 0) {
                return cached.entries();
            }
        }
        List<LogEntry> entries = readSource(source);
        cache.put(id, new CachedEntries(entries, Instant.now(), safeFingerprint(source)));
        return entries;
    }

    /** Drops all cached entries so the next read re-fetches every source, live or not. */
    public void invalidateAll() {
        cache.clear();
        probeCache.clear();
        log.info("Ingestion cache invalidated for all sources");
    }

    /**
     * Which specific file(s) within a non-live source look like they've
     * changed since the source was last read - the signal behind the "new
     * data available" indicator, since non-live sources otherwise stay frozen
     * until reloaded. Fingerprints are tracked per file (not aggregated per
     * source) specifically so a LOCAL_DIRECTORY source can report exactly
     * which file changed instead of just "the directory changed". Live
     * sources always report empty here since they already refresh on their
     * own. Probing (which can mean a real SFTP connection or HTTP HEAD
     * request) is itself throttled by a short TTL so frequent polling from
     * the UI doesn't hammer a remote source just to answer this question.
     */
    public List<String> changedFiles(LogSource source) {
        if (!source.isEnabled() || source.isLive()) {
            return List.of();
        }
        Long id = source.getId();
        if (id == null) {
            return List.of();
        }
        CachedEntries cached = cache.get(id);
        if (cached == null || cached.fingerprints() == null) {
            return List.of();
        }

        ProbeResult probed = probeCache.get(id);
        if (probed != null && Duration.between(probed.checkedAt(), Instant.now()).compareTo(PROBE_TTL) < 0) {
            return probed.changedFiles();
        }

        List<String> changed;
        try {
            changed = diffFingerprints(cached.fingerprints(), fingerprintOf(source));
        } catch (Exception e) {
            changed = List.of();
        }
        probeCache.put(id, new ProbeResult(changed, Instant.now()));
        return changed;
    }

    private List<String> diffFingerprints(
            Map<String, TailSource.Fingerprint> previous, Map<String, TailSource.Fingerprint> current) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, TailSource.Fingerprint> entry : current.entrySet()) {
            TailSource.Fingerprint prior = previous.get(entry.getKey());
            if (prior == null || !prior.equals(entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        return changed;
    }

    private List<LogEntry> readSource(LogSource source) {
        try {
            return switch (source.getType()) {
                case LOCAL_FILE -> readLocalFile(Path.of(source.getPath()), source.getName());
                case LOCAL_DIRECTORY -> readLocalDirectory(Path.of(source.getPath()), source.getName());
                case SFTP -> readRemoteTail(sftpTailSource(source), source.getName(), basenameOf(source.getPath()));
                case HTTP -> readRemoteTail(new HttpTailSource(URI.create(source.getPath())), source.getName(), basenameOf(source.getPath()));
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
        return toEntries(lines, sourceName, path.getFileName().toString());
    }

    private List<LogEntry> readLocalDirectory(Path dir, String sourceName) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of(errorEntry(sourceName, "not a directory: " + dir));
        }

        List<LogEntry> entries = new ArrayList<>();
        for (Path file : selectDirectoryFiles(dir)) {
            List<String> lines = LogTailReader.readLastLines(new LocalTailSource(file), MAX_TAIL_BYTES, MAX_LINES_PER_FILE);
            entries.addAll(toEntries(lines, sourceName, file.getFileName().toString()));
        }
        return entries;
    }

    /** Same most-recently-modified selection used for both reading and probing, so the two stay in sync. */
    private List<Path> selectDirectoryFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(this::lastModifiedSafe).reversed())
                    .limit(MAX_FILES_PER_DIRECTORY)
                    .toList();
        }
    }

    private Map<String, TailSource.Fingerprint> safeFingerprint(LogSource source) {
        try {
            return fingerprintOf(source);
        } catch (Exception e) {
            return null;
        }
    }

    /** One fingerprint per file, keyed by the same file label toEntries() tags entries with. */
    private Map<String, TailSource.Fingerprint> fingerprintOf(LogSource source) throws IOException {
        return switch (source.getType()) {
            case LOCAL_FILE -> {
                Path path = Path.of(source.getPath());
                yield Map.of(path.getFileName().toString(), new LocalTailSource(path).probe());
            }
            case LOCAL_DIRECTORY -> directoryFingerprints(Path.of(source.getPath()));
            case SFTP -> Map.of(basenameOf(source.getPath()), sftpTailSource(source).probe());
            case HTTP -> Map.of(basenameOf(source.getPath()), new HttpTailSource(URI.create(source.getPath())).probe());
        };
    }

    private Map<String, TailSource.Fingerprint> directoryFingerprints(Path dir) throws IOException {
        Map<String, TailSource.Fingerprint> fingerprints = new LinkedHashMap<>();
        for (Path file : selectDirectoryFiles(dir)) {
            fingerprints.put(file.getFileName().toString(), new LocalTailSource(file).probe());
        }
        return fingerprints;
    }

    private List<LogEntry> readRemoteTail(TailSource tailSource, String sourceName, String fileLabel) throws IOException {
        List<String> lines = LogTailReader.readLastLines(tailSource, MAX_TAIL_BYTES, MAX_LINES_PER_FILE);
        return toEntries(lines, sourceName, fileLabel);
    }

    private List<LogEntry> toEntries(List<String> lines, String sourceName, String fileLabel) {
        List<LogEntry> entries = new ArrayList<>();
        for (LogLineParser.ParsedLine parsed : LogLineParser.parse(lines)) {
            entries.add(new LogEntry(0, parsed.timestamp(), parsed.level(), sourceName, fileLabel, parsed.message()));
        }
        return entries;
    }

    /** Derives a display-friendly file label from a local path, remote path, or URL. */
    private static String basenameOf(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String trimmed = rawPath.replaceAll("[/\\\\]+$", "");
        int idx = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
    }

    private Instant lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private LogEntry errorEntry(String sourceName, String reason) {
        return new LogEntry(0, Instant.now(), LogLevel.ERROR, sourceName, null, "Failed to read log source: " + reason);
    }

    private record CachedEntries(List<LogEntry> entries, Instant fetchedAt, Map<String, TailSource.Fingerprint> fingerprints) {
    }

    private record ProbeResult(List<String> changedFiles, Instant checkedAt) {
    }
}
