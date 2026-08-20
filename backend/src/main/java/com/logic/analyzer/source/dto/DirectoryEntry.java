package com.logic.analyzer.source.dto;

/** One child of a browsed directory - see {@link DirectoryListing}. */
public record DirectoryEntry(String name, String path, boolean directory) {
}
