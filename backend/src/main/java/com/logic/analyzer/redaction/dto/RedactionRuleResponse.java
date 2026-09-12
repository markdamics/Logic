package com.logic.analyzer.redaction.dto;

import com.logic.analyzer.redaction.RedactionRule;

import java.time.Instant;

public record RedactionRuleResponse(
        Long id,
        String name,
        String pattern,
        String replacement,
        String source,
        boolean enabled,
        Instant createdAt
) {
    public static RedactionRuleResponse from(RedactionRule rule) {
        return new RedactionRuleResponse(
                rule.getId(),
                rule.getName(),
                rule.getPattern(),
                rule.getReplacement(),
                rule.getSource(),
                rule.isEnabled(),
                rule.getCreatedAt()
        );
    }
}
