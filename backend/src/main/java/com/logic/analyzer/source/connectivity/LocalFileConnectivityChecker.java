package com.logic.analyzer.source.connectivity;

import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.SourceStatus;
import com.logic.analyzer.source.SourceType;
import com.logic.analyzer.source.dto.ConnectionTestResult;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

@Component
public class LocalFileConnectivityChecker implements SourceConnectivityChecker {

    @Override
    public Set<SourceType> supports() {
        return Set.of(SourceType.LOCAL_FILE, SourceType.LOCAL_DIRECTORY);
    }

    @Override
    public ConnectionTestResult check(LogSource source) {
        Path path = Path.of(source.getPath());
        Instant now = Instant.now();

        if (!Files.exists(path)) {
            return new ConnectionTestResult(SourceStatus.UNREACHABLE, "Path does not exist: " + path, now);
        }
        if (!Files.isReadable(path)) {
            return new ConnectionTestResult(SourceStatus.UNREACHABLE, "Path is not readable: " + path, now);
        }
        if (source.getType() == SourceType.LOCAL_DIRECTORY && !Files.isDirectory(path)) {
            return new ConnectionTestResult(SourceStatus.UNREACHABLE, "Path is not a directory: " + path, now);
        }
        if (source.getType() == SourceType.LOCAL_FILE && Files.isDirectory(path)) {
            return new ConnectionTestResult(SourceStatus.UNREACHABLE, "Path is a directory, expected a file: " + path, now);
        }
        return new ConnectionTestResult(SourceStatus.REACHABLE, "Path exists and is readable", now);
    }
}
