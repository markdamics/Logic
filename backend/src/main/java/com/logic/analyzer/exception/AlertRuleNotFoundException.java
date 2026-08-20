package com.logic.analyzer.exception;

public class AlertRuleNotFoundException extends RuntimeException {

    public AlertRuleNotFoundException(Long id) {
        super("Alert rule not found: " + id);
    }
}
