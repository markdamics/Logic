package com.logic.analyzer.redaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RedactionRuleCreateRequest(
        @NotBlank String name,
        @NotBlank @Size(max = 1000) String pattern,
        String replacement,
        String source,
        boolean enabled
) {
}
