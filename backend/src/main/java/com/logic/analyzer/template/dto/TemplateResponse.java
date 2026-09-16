package com.logic.analyzer.template.dto;

import com.logic.analyzer.template.LogTemplate;

import java.time.Instant;
import java.util.List;

public record TemplateResponse(
        Long id,
        String source,
        String file,
        String templateText,
        long occurrenceCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        String sampleRawLine,
        List<Long> history,
        /** Set only when this template was created by a LOGIC-119 split; null otherwise. */
        String splitFromTemplateText
) {
    public static TemplateResponse from(LogTemplate template) {
        return new TemplateResponse(
                template.getId(),
                template.getSource(),
                template.getFile(),
                template.getTemplateText(),
                template.getOccurrenceCount(),
                template.getFirstSeenAt(),
                template.getLastSeenAt(),
                template.getSampleRawLine(),
                template.getHistoryCounts(),
                template.getSplitFromTemplateText());
    }
}
