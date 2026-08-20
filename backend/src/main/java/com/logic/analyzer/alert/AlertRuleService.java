package com.logic.analyzer.alert;

import com.logic.analyzer.alert.dto.AlertEventResponse;
import com.logic.analyzer.alert.dto.AlertRuleCreateRequest;
import com.logic.analyzer.alert.dto.AlertRuleResponse;
import com.logic.analyzer.exception.AlertRuleNotFoundException;
import com.logic.analyzer.search.query.QueryLanguage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class AlertRuleService {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleService.class);

    private final AlertRuleRepository repository;
    private final AlertEventRepository eventRepository;
    private final WebhookNotifier webhookNotifier;

    public AlertRuleService(AlertRuleRepository repository, AlertEventRepository eventRepository,
                             WebhookNotifier webhookNotifier) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.webhookNotifier = webhookNotifier;
    }

    public List<AlertRuleResponse> listAll() {
        return repository.findAll().stream().map(AlertRuleResponse::from).toList();
    }

    public AlertRuleResponse create(AlertRuleCreateRequest request) {
        validate(request);

        AlertRule rule = new AlertRule(
                request.name(), request.queryLanguage(), request.query(), request.search(), request.levels(),
                request.source(), request.file(), request.ruleType(), request.windowMinutes(), request.metric(),
                request.comparisonOp(), request.threshold(), request.anomalyBaselineWindows(),
                request.anomalyStdDevMultiplier(), request.webhookUrl(), request.webhookSecret());
        AlertRule saved = repository.save(rule);
        log.info("Created alert rule '{}' (id={}, type={})", saved.getName(), saved.getId(), saved.getRuleType());
        return AlertRuleResponse.from(saved);
    }

    public AlertRuleResponse update(Long id, AlertRuleCreateRequest request) {
        validate(request);

        AlertRule rule = repository.findById(id).orElseThrow(() -> new AlertRuleNotFoundException(id));
        rule.update(request.name(), request.queryLanguage(), request.query(), request.search(), request.levels(),
                request.source(), request.file(), request.ruleType(), request.windowMinutes(), request.metric(),
                request.comparisonOp(), request.threshold(), request.anomalyBaselineWindows(),
                request.anomalyStdDevMultiplier(), request.webhookUrl(), request.webhookSecret());
        AlertRule saved = repository.save(rule);
        log.info("Updated alert rule {} -> '{}'", id, saved.getName());
        return AlertRuleResponse.from(saved);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new AlertRuleNotFoundException(id);
        }
        repository.deleteById(id);
        log.info("Deleted alert rule {}", id);
    }

    public AlertRuleResponse setMuted(Long id, boolean muted) {
        AlertRule rule = repository.findById(id).orElseThrow(() -> new AlertRuleNotFoundException(id));
        rule.setMuted(muted);
        AlertRule saved = repository.save(rule);
        log.info("{} alert rule {} ('{}')", muted ? "Muted" : "Unmuted", id, saved.getName());
        return AlertRuleResponse.from(saved);
    }

    public List<AlertEventResponse> recentEvents(Long id) {
        if (!repository.existsById(id)) {
            throw new AlertRuleNotFoundException(id);
        }
        return eventRepository.findTop50ByAlertRuleIdOrderByTriggeredAtDesc(id).stream()
                .map(AlertEventResponse::from).toList();
    }

    /** Fires the rule's webhook once with the rule's current definition but no real trigger - lets the UI offer a "send test" button next to the URL field. */
    public void testWebhook(Long id) {
        AlertRule rule = repository.findById(id).orElseThrow(() -> new AlertRuleNotFoundException(id));
        if (rule.getWebhookUrl() == null || rule.getWebhookUrl().isBlank()) {
            throw new IllegalArgumentException("This rule has no webhook URL configured");
        }
        webhookNotifier.notifyAsync(rule, Instant.now(), 0, status -> { });
    }

    private void validate(AlertRuleCreateRequest request) {
        if (request.queryLanguage() != QueryLanguage.SIMPLE
                && (request.query() == null || request.query().isBlank())) {
            throw new IllegalArgumentException("query is required for a " + request.queryLanguage() + " alert rule");
        }
        if (request.ruleType() == AlertRuleType.THRESHOLD) {
            if (request.comparisonOp() == null || request.threshold() == null) {
                throw new IllegalArgumentException("comparisonOp and threshold are required for a THRESHOLD rule");
            }
        } else {
            if (request.anomalyBaselineWindows() == null || request.anomalyBaselineWindows() < 1
                    || request.anomalyStdDevMultiplier() == null || request.anomalyStdDevMultiplier() <= 0) {
                throw new IllegalArgumentException(
                        "anomalyBaselineWindows (>=1) and anomalyStdDevMultiplier (>0) are required for an ANOMALY rule");
            }
        }
    }
}
