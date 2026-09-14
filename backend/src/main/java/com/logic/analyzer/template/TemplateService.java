package com.logic.analyzer.template;

import com.logic.analyzer.template.dto.TemplateResponse;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class TemplateService {

    private final LogTemplateRepository repository;

    public TemplateService(LogTemplateRepository repository) {
        this.repository = repository;
    }

    /**
     * @param source optional exact-match scope filter; unset lists across every source.
     * @param sort   "recent" sorts by when a template first appeared, most-recent first;
     *               anything else (including unset) sorts by occurrence volume, highest first.
     */
    public List<TemplateResponse> list(String source, String sort) {
        List<LogTemplate> templates = (source == null || source.isBlank())
                ? repository.findAll()
                : repository.findBySource(source);

        Comparator<LogTemplate> comparator = "recent".equalsIgnoreCase(sort)
                ? Comparator.comparing(LogTemplate::getFirstSeenAt).reversed()
                : Comparator.comparingLong(LogTemplate::getOccurrenceCount).reversed();

        return templates.stream().sorted(comparator).map(TemplateResponse::from).toList();
    }
}
