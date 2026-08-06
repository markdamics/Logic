package com.logic.analyzer.exception;

public class SourceNotFoundException extends RuntimeException {

    public SourceNotFoundException(Long id) {
        super("Log source not found: " + id);
    }
}
