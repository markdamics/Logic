package com.logic.analyzer.exception;

public class SavedSearchNotFoundException extends RuntimeException {

    public SavedSearchNotFoundException(Long id) {
        super("Saved search not found: " + id);
    }
}
