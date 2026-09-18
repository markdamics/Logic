package com.logic.analyzer.redaction;

import com.logic.analyzer.audit.AuditEntityType;
import com.logic.analyzer.audit.AuditService;
import com.logic.analyzer.exception.RedactionRuleNotFoundException;
import com.logic.analyzer.redaction.dto.RedactionRuleCreateRequest;
import com.logic.analyzer.redaction.dto.RedactionRuleResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;


@Service
public class RedactionRuleService {

    private static final Logger log = LoggerFactory.getLogger(RedactionRuleService.class);
    private static final String DEFAULT_MASK = "***";

    private final RedactionRuleRepository repository;
    private final AuditService auditService;

    public RedactionRuleService(RedactionRuleRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    public List<RedactionRuleResponse> listAll() {
        return repository.findAll().stream().map(RedactionRuleResponse::from).toList();
    }

    public RedactionRuleResponse create(RedactionRuleCreateRequest request) {
        validatePattern(request.pattern());
        RedactionRule rule = new RedactionRule(
                request.name(), request.pattern(), request.replacement(), request.source(), request.enabled());
        RedactionRule saved = repository.save(rule);
        auditService.recordCreate(AuditEntityType.REDACTION_RULE, saved.getId(), saved.getName(), snapshot(saved));
        log.info("Created redaction rule '{}' (id={}, scope={})", saved.getName(), saved.getId(),
                saved.getSource() == null ? "global" : saved.getSource());
        return RedactionRuleResponse.from(saved);
    }

    public RedactionRuleResponse update(Long id, RedactionRuleCreateRequest request) {
        validatePattern(request.pattern());
        RedactionRule rule = repository.findById(id).orElseThrow(() -> new RedactionRuleNotFoundException(id));
        Map<String, Object> before = snapshot(rule);
        rule.update(request.name(), request.pattern(), request.replacement(), request.source(), request.enabled());
        RedactionRule saved = repository.save(rule);
        auditService.recordUpdate(AuditEntityType.REDACTION_RULE, id, saved.getName(), before, snapshot(saved));
        log.info("Updated redaction rule {} -> '{}'", id, saved.getName());
        return RedactionRuleResponse.from(saved);
    }

    public void delete(Long id) {
        RedactionRule rule = repository.findById(id).orElseThrow(() -> new RedactionRuleNotFoundException(id));
        Map<String, Object> before = snapshot(rule);
        repository.deleteById(id);
        auditService.recordDelete(AuditEntityType.REDACTION_RULE, id, rule.getName(), before);
        log.info("Deleted redaction rule {}", id);
    }

    /** Mirrors {@link RedactionRuleResponse} field-for-field; unlike LogSource/AlertRule, nothing here is a secret. */
    private Map<String, Object> snapshot(RedactionRule rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", rule.getName());
        map.put("pattern", rule.getPattern());
        map.put("replacement", rule.getReplacement());
        map.put("source", rule.getSource());
        map.put("enabled", rule.isEnabled());
        return map;
    }

    /**
     * Compiles every enabled rule that applies globally or to {@code sourceName},
     * once per source read rather than once per line - callers apply the result
     * across a whole batch via {@link #redact(String, List)}.
     */
    public List<CompiledRule> rulesFor(String sourceName) {
        List<CompiledRule> compiled = new ArrayList<>();
        for (RedactionRule rule : repository.findAll()) {
            if (!rule.isEnabled()) {
                continue;
            }
            if (rule.getSource() != null && !rule.getSource().isBlank() && !rule.getSource().equals(sourceName)) {
                continue;
            }
            try {
                compiled.add(new CompiledRule(Pattern.compile(rule.getPattern()), maskOrDefault(rule.getReplacement())));
            } catch (PatternSyntaxException e) {
                log.warn("Skipping redaction rule '{}' (id={}) with invalid pattern: {}", rule.getName(), rule.getId(), e.getMessage());
            }
        }
        return compiled;
    }

    /** Applies every compiled rule's mask in order; a null message or an empty rule list is returned unchanged. */
    public static String redact(String message, List<CompiledRule> rules) {
        if (message == null || rules.isEmpty()) {
            return message;
        }
        String result = message;
        for (CompiledRule rule : rules) {
            result = rule.pattern().matcher(result).replaceAll(Matcher.quoteReplacement(rule.replacement()));
        }
        return result;
    }

    private static String maskOrDefault(String replacement) {
        return replacement == null || replacement.isBlank() ? DEFAULT_MASK : replacement;
    }

    private void validatePattern(String pattern) {
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage());
        }
    }

    public record CompiledRule(Pattern pattern, String replacement) {
    }
}
