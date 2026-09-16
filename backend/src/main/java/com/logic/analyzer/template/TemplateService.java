package com.logic.analyzer.template;

import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.LogSourceRepository;
import com.logic.analyzer.template.dto.TemplateResponse;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TemplateService {

    private final LogTemplateRepository repository;
    private final LogSourceRepository sourceRepository;

    public TemplateService(LogTemplateRepository repository, LogSourceRepository sourceRepository) {
        this.repository = repository;
        this.sourceRepository = sourceRepository;
    }

    /**
     * @param source optional exact-match scope filter; unset lists across every source.
     * @param file   optional exact-match file filter, composable independently of
     *               {@code source} - mirrors LogQueryService's own source/file filters,
     *               which likewise combine rather than requiring one before the other.
     * @param sort   "recent" sorts by when a template first appeared, most-recent first;
     *               anything else (including unset) sorts by occurrence volume, highest first.
     */
    public List<TemplateResponse> list(String source, String file, String sort) {
        boolean hasSource = source != null && !source.isBlank();
        boolean hasFile = file != null && !file.isBlank();
        List<LogTemplate> templates;
        if (hasSource && hasFile) {
            templates = repository.findBySourceAndFile(source, file);
        } else if (hasSource) {
            templates = repository.findBySource(source);
        } else if (hasFile) {
            templates = repository.findByFile(file);
        } else {
            templates = repository.findAll();
        }

        // Pattern mining is opt-in per source (LogSource.patternMiningEnabled). A template
        // row can outlive that being turned off - either because it was mined before the
        // toggle existed, or because it was mined during an earlier stretch where the
        // source had it enabled - and it must not resurface here just because the row is
        // still in the table: disabling the feature should make the source behave as if
        // nothing had ever been mined, not just pause future mining.
        Set<String> miningEnabledSources = sourceRepository.findAll().stream()
                .filter(LogSource::isPatternMiningEnabled)
                .map(LogSource::getName)
                .collect(Collectors.toSet());

        Comparator<LogTemplate> comparator = "recent".equalsIgnoreCase(sort)
                ? Comparator.comparing(LogTemplate::getFirstSeenAt).reversed()
                : Comparator.comparingLong(LogTemplate::getOccurrenceCount).reversed();

        return templates.stream()
                .filter(t -> miningEnabledSources.contains(t.getSource()))
                // A null file means this row predates per-file scoping (LOGIC-107 follow-up) -
                // it can never be matched by a new occurrence again (mining always supplies a
                // real file), so it's permanently frozen/orphaned. Not worth surfacing as a
                // "pattern" going forward; it just ages out under retention like any other row.
                .filter(t -> t.getFile() != null)
                .sorted(comparator)
                .map(TemplateResponse::from)
                .toList();
    }
}
