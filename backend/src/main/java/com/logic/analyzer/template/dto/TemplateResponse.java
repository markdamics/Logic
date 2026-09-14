package com.logic.analyzer.template.dto;

import com.logic.analyzer.template.LogTemplate;

import java.time.Instant;
import java.util.List;

public record TemplateResponse(
        Long id,
        String source,
        String templateText,
        long occurrenceCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        String sampleRawLine,
        List<Long> history
) {
    public static TemplateResponse from(LogTemplate template) {
        return new TemplateResponse(
                template.getId(),
                template.getSource(),
                template.getTemplateText(),
                template.getOccurrenceCount(),
                template.getFirstSeenAt(),
                template.getLastSeenAt(),
                template.getSampleRawLine(),
                template.getHistoryCounts());
    }
}
