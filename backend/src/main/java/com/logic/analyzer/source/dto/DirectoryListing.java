package com.logic.analyzer.source.dto;

import java.util.List;

/**
 * Response for {@code GET /api/sources/browse} - the Sources dialog's
 * file/directory picker. {@code parent} is null at the filesystem root, so
 * the frontend knows when to stop offering an "up" action.
 */
public record DirectoryListing(String path, String parent, List<DirectoryEntry> entries) {
}
