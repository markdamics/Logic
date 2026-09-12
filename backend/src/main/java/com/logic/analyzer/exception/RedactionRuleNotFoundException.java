package com.logic.analyzer.exception;

public class RedactionRuleNotFoundException extends RuntimeException {

    public RedactionRuleNotFoundException(Long id) {
        super("Redaction rule not found: " + id);
    }
}
