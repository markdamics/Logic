package com.logic.analyzer.source.dto;

import com.logic.analyzer.source.SourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Which fields are required depends on {@code type} (e.g. host/username only apply
 * to SFTP). Bean Validation annotations can't express that conditional requirement,
 * so type-specific checks are done in LogSourceService.create(...) instead.
 */
public record LogSourceCreateRequest(
        @NotBlank String name,
        @NotNull SourceType type,
        String path,
        String host,
        Integer port,
        String username,
        String password
) {
}
