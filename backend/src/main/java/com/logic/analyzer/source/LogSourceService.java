package com.logic.analyzer.source;

import com.logic.analyzer.exception.SourceNotFoundException;
import com.logic.analyzer.logstream.LogIngestionService;
import com.logic.analyzer.search.index.SearchIndexService;
import com.logic.analyzer.source.connectivity.SourceConnectivityChecker;
import com.logic.analyzer.source.dto.ConnectionTestResult;
import com.logic.analyzer.source.dto.LogSourceCreateRequest;
import com.logic.analyzer.source.dto.LogSourceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class LogSourceService {

    private static final Logger log = LoggerFactory.getLogger(LogSourceService.class);

    private final LogSourceRepository repository;
    private final LogIngestionService ingestionService;
    private final SearchIndexService searchIndexService;
    private final SourceUploadService uploadService;
    private final Map<SourceType, SourceConnectivityChecker> checkersByType;

    public LogSourceService(LogSourceRepository repository, LogIngestionService ingestionService,
                             SearchIndexService searchIndexService, SourceUploadService uploadService,
                             List<SourceConnectivityChecker> checkers) {
        this.repository = repository;
        this.ingestionService = ingestionService;
        this.searchIndexService = searchIndexService;
        this.uploadService = uploadService;
        this.checkersByType = new EnumMap<>(SourceType.class);
        for (SourceConnectivityChecker checker : checkers) {
            for (SourceType type : checker.supports()) {
                checkersByType.put(type, checker);
            }
        }
    }

    public List<LogSourceResponse> listAll() {
        return repository.findAll().stream()
                .map(source -> LogSourceResponse.from(source, ingestionService.changedFiles(source)))
                .toList();
    }

    public LogSourceResponse create(LogSourceCreateRequest request) {
        validateTypeSpecificFields(request);

        LogSource source = new LogSource(
                request.name(),
                request.type(),
                request.path(),
                request.host(),
                request.port(),
                request.username(),
                request.password()
        );
        LogSource saved = repository.save(source);
        log.info("Created {} source '{}' (id={})", saved.getType(), saved.getName(), saved.getId());
        return LogSourceResponse.from(saved);
    }

    public LogSourceResponse update(Long id, LogSourceCreateRequest request) {
        validateTypeSpecificFields(request);

        LogSource source = repository.findById(id).orElseThrow(() -> new SourceNotFoundException(id));
        source.update(
                request.name(),
                request.type(),
                request.path(),
                request.host(),
                request.port(),
                request.username(),
                request.password()
        );
        LogSource saved = repository.save(source);
        log.info("Updated source {} -> '{}' ({})", id, saved.getName(), saved.getType());
        return LogSourceResponse.from(saved);
    }

    public LogSourceResponse setEnabled(Long id, boolean enabled) {
        LogSource source = repository.findById(id).orElseThrow(() -> new SourceNotFoundException(id));
        source.setEnabled(enabled);
        LogSource saved = repository.save(source);
        log.info("{} source {} ('{}')", enabled ? "Enabled" : "Disabled", id, saved.getName());
        return LogSourceResponse.from(saved);
    }

    public LogSourceResponse setLive(Long id, boolean live) {
        LogSource source = repository.findById(id).orElseThrow(() -> new SourceNotFoundException(id));
        if (live && (source.getType() == SourceType.UPLOAD_FILE || source.getType() == SourceType.UPLOAD_DIRECTORY)) {
            throw new IllegalArgumentException("Live mode is not supported for uploaded sources");
        }
        source.setLive(live);
        LogSource saved = repository.save(source);
        log.info("Marked source {} ('{}') as {}", id, saved.getName(), live ? "live" : "not live");
        return LogSourceResponse.from(saved);
    }

    public void delete(Long id) {
        LogSource source = repository.findById(id).orElseThrow(() -> new SourceNotFoundException(id));
        repository.deleteById(id);
        searchIndexService.purgeSource(source);
        uploadService.deleteStorage(source);
        log.info("Deleted source {}", id);
    }

    public ConnectionTestResult testConnection(Long id) {
        LogSource source = repository.findById(id).orElseThrow(() -> new SourceNotFoundException(id));
        SourceConnectivityChecker checker = checkersByType.get(source.getType());

        log.info("Testing connection for source {} ('{}', {})", id, source.getName(), source.getType());
        ConnectionTestResult result = checker.check(source);

        source.setStatus(result.status());
        source.setLastCheckedAt(result.checkedAt());
        repository.save(source);

        if (result.status() == SourceStatus.REACHABLE) {
            log.info("Source {} is reachable: {}", id, result.message());
        } else {
            log.warn("Source {} is unreachable: {}", id, result.message());
        }

        return result;
    }

    private void validateTypeSpecificFields(LogSourceCreateRequest request) {
        switch (request.type()) {
            case LOCAL_FILE, LOCAL_DIRECTORY, UPLOAD_FILE, UPLOAD_DIRECTORY -> requireNonBlank(request.path(), "path");
            case SFTP -> {
                requireNonBlank(request.host(), "host");
                requireNonBlank(request.username(), "username");
                requireNonBlank(request.path(), "path");
            }
            case HTTP -> requireValidUrl(request.path());
        }
    }

    private void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required for this source type");
        }
    }

    private void requireValidUrl(String path) {
        requireNonBlank(path, "path");
        if (!(path.startsWith("http://") || path.startsWith("https://"))) {
            throw new IllegalArgumentException("path must be a valid http:// or https:// URL");
        }
    }
}
