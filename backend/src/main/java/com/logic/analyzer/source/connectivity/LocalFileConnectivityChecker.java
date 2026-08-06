package com.logic.analyzer.source.connectivity;

import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.SourceStatus;
import com.logic.analyzer.source.SourceType;
import com.logic.analyzer.source.dto.ConnectionTestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

@Component
public class LocalFileConnectivityChecker implements SourceConnectivityChecker {

    private static final Logger log = LoggerFactory.getLogger(LocalFileConnectivityChecker.class);

    @Override
    public Set<SourceType> supports() {
        return Set.of(SourceType.LOCAL_FILE, SourceType.LOCAL_DIRECTORY);
    }

    @Override
    public ConnectionTestResult check(LogSource source) {
        Path path = Path.of(source.getPath());
        Instant now = Instant.now();
        log.debug("Checking local {} at {}", source.getType(), path);

        if (!Files.exists(path)) {
            log.warn("Local path does not exist: {}", path);
            return new ConnectionTestResult(SourceStatus.UNREACHABLE, "Path does not exist: " + path, now);
        }
        if (!Files.isReadable(path)) {
            log.warn("Local path is not readable: {}", path);
            return new ConnectionTestResult(SourceStatus.UNREACHABLE, "Path is not readable: " + path, now);
        }
        if (source.getType() == SourceType.LOCAL_DIRECTORY && !Files.isDirectory(path)) {
            log.warn("Expected a directory but found a file: {}", path);
            return new ConnectionTestResult(SourceStatus.UNREACHABLE, "Path is not a directory: " + path, now);
        }
        if (source.getType() == SourceType.LOCAL_FILE && Files.isDirectory(path)) {
            log.warn("Expected a file but found a directory: {}", path);
            return new ConnectionTestResult(SourceStatus.UNREACHABLE, "Path is a directory, expected a file: " + path, now);
        }
        return new ConnectionTestResult(SourceStatus.REACHABLE, "Path exists and is readable", now);
    }
}
