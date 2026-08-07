package com.logic.analyzer.source;

import com.logic.analyzer.source.dto.ConnectionTestResult;
import com.logic.analyzer.source.dto.LogSourceCreateRequest;
import com.logic.analyzer.source.dto.LogSourceResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sources")
public class LogSourceController {

    private final LogSourceService service;

    public LogSourceController(LogSourceService service) {
        this.service = service;
    }

    @GetMapping
    public List<LogSourceResponse> list() {
        return service.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LogSourceResponse create(@Valid @RequestBody LogSourceCreateRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public LogSourceResponse update(@PathVariable Long id, @Valid @RequestBody LogSourceCreateRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/test-connection")
    public ConnectionTestResult testConnection(@PathVariable Long id) {
        return service.testConnection(id);
    }

    @PostMapping("/{id}/enable")
    public LogSourceResponse enable(@PathVariable Long id) {
        return service.setEnabled(id, true);
    }

    @PostMapping("/{id}/disable")
    public LogSourceResponse disable(@PathVariable Long id) {
        return service.setEnabled(id, false);
    }

    @PostMapping("/{id}/enable-live")
    public LogSourceResponse enableLive(@PathVariable Long id) {
        return service.setLive(id, true);
    }

    @PostMapping("/{id}/disable-live")
    public LogSourceResponse disableLive(@PathVariable Long id) {
        return service.setLive(id, false);
    }
}
