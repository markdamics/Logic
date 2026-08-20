package com.logic.analyzer.alert;

import com.logic.analyzer.alert.dto.AlertEventResponse;
import com.logic.analyzer.alert.dto.AlertRuleCreateRequest;
import com.logic.analyzer.alert.dto.AlertRuleResponse;
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
@RequestMapping("/api/alerts/rules")
public class AlertRuleController {

    private final AlertRuleService service;

    public AlertRuleController(AlertRuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<AlertRuleResponse> list() {
        return service.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlertRuleResponse create(@Valid @RequestBody AlertRuleCreateRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public AlertRuleResponse update(@PathVariable Long id, @Valid @RequestBody AlertRuleCreateRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/mute")
    public AlertRuleResponse mute(@PathVariable Long id) {
        return service.setMuted(id, true);
    }

    @PostMapping("/{id}/unmute")
    public AlertRuleResponse unmute(@PathVariable Long id) {
        return service.setMuted(id, false);
    }

    @GetMapping("/{id}/events")
    public List<AlertEventResponse> events(@PathVariable Long id) {
        return service.recentEvents(id);
    }

    @PostMapping("/{id}/test-webhook")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void testWebhook(@PathVariable Long id) {
        service.testWebhook(id);
    }
}
