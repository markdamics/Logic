package com.logic.analyzer.source;

import com.logic.analyzer.exception.SourceNotFoundException;
import com.logic.analyzer.source.connectivity.SourceConnectivityChecker;
import com.logic.analyzer.source.dto.ConnectionTestResult;
import com.logic.analyzer.source.dto.LogSourceCreateRequest;
import com.logic.analyzer.source.dto.LogSourceResponse;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class LogSourceService {

    private final LogSourceRepository repository;
    private final Map<SourceType, SourceConnectivityChecker> checkersByType;

    public LogSourceService(LogSourceRepository repository, List<SourceConnectivityChecker> checkers) {
        this.repository = repository;
        this.checkersByType = new EnumMap<>(SourceType.class);
        for (SourceConnectivityChecker checker : checkers) {
            for (SourceType type : checker.supports()) {
                checkersByType.put(type, checker);
            }
        }
    }

    public List<LogSourceResponse> listAll() {
        return repository.findAll().stream().map(LogSourceResponse::from).toList();
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
        return LogSourceResponse.from(repository.save(source));
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
        return LogSourceResponse.from(repository.save(source));
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new SourceNotFoundException(id);
        }
        repository.deleteById(id);
    }

    public ConnectionTestResult testConnection(Long id) {
        LogSource source = repository.findById(id).orElseThrow(() -> new SourceNotFoundException(id));
        SourceConnectivityChecker checker = checkersByType.get(source.getType());
        ConnectionTestResult result = checker.check(source);

        source.setStatus(result.status());
        source.setLastCheckedAt(result.checkedAt());
        repository.save(source);

        return result;
    }

    private void validateTypeSpecificFields(LogSourceCreateRequest request) {
        switch (request.type()) {
            case LOCAL_FILE, LOCAL_DIRECTORY -> requireNonBlank(request.path(), "path");
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
