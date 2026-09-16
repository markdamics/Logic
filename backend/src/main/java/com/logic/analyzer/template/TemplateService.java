package com.logic.analyzer.template;

import com.logic.analyzer.exception.TemplateNotFoundException;
import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.logstream.LogQueryParams;
import com.logic.analyzer.logstream.LogQueryService;
import com.logic.analyzer.search.index.SearchIndexService;
import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.LogSourceRepository;
import com.logic.analyzer.template.dto.TemplateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TemplateService {

    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);

    /** Cap on currently-indexed lines considered for a manual split - a one-shot admin action on one over-generalized template, not an unbounded export. */
    private static final int MAX_SPLIT_SAMPLE = 5000;

    /** Stricter than TemplateMiningService's normal SIMILARITY_THRESHOLD (0.5) - a split needs to actually separate members that mining already judged similar enough to merge once. */
    private static final double SPLIT_SIMILARITY_THRESHOLD = 0.85;

    private final LogTemplateRepository repository;
    private final LogSourceRepository sourceRepository;
    private final TemplateMiningService miningService;
    private final LogQueryService logQueryService;
    private final SearchIndexService searchIndexService;

    public TemplateService(LogTemplateRepository repository, LogSourceRepository sourceRepository,
                            TemplateMiningService miningService, LogQueryService logQueryService,
                            SearchIndexService searchIndexService) {
        this.repository = repository;
        this.sourceRepository = sourceRepository;
        this.miningService = miningService;
        this.logQueryService = logQueryService;
        this.searchIndexService = searchIndexService;
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

    /**
     * Doesn't retroactively reclassify already-counted history - the next occurrence of this
     * message shape simply mints a fresh template, exactly like a shape mining has never seen
     * before. Mirrors {@link TemplateRetentionJob#purgeExpired()}'s cache handling: the in-memory
     * per-source cluster cache may still hold a reference to the row just deleted, so it's
     * cleared rather than risking a subsequent mine() silently no-op-updating a deleted row.
     */
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new TemplateNotFoundException(id);
        }
        repository.deleteById(id);
        miningService.invalidateCache();
        log.info("Deleted template {}", id);
    }

    /**
     * Breaks one over-generalized template into several, for the case where mining folded two
     * genuinely distinct message shapes together. Re-clusters the currently-indexed lines tagged
     * with this template's id (LOGIC-117's drill-down filter, repurposed here) at a stricter
     * threshold, in isolation from every other template for this (source, file) - see
     * {@link TemplateMiningService#recluster} for why the origin template itself can't be a
     * candidate. Only ever considers what's <em>currently indexed</em>: a template whose
     * occurrences have mostly scrolled out of the tail window can't be split from history it no
     * longer has, so its occurrenceCount here is a recount of what's still indexed, not a
     * partition of the original aggregate.
     */
    public List<TemplateResponse> split(Long id) {
        LogTemplate target = repository.findById(id).orElseThrow(() -> new TemplateNotFoundException(id));

        LogQueryParams params = new LogQueryParams(
                null, Set.of(), target.getSource(), target.getFile(), target.getId(), 0, "time", "desc", 0, MAX_SPLIT_SAMPLE);
        List<LogEntry> matching = logQueryService.query(params).content();
        if (matching.isEmpty()) {
            throw new IllegalArgumentException(
                    "No currently indexed lines match this template - they may have scrolled out of the retained tail window, so there's nothing left to split");
        }

        List<String> distinctMessages = matching.stream().map(LogEntry::message).distinct().toList();
        List<TemplateMiningService.ReclusterGroup> groups = miningService.recluster(distinctMessages, SPLIT_SIMILARITY_THRESHOLD);
        if (groups.size() < 2) {
            throw new IllegalArgumentException(
                    "These lines don't separate into more than one pattern even at a stricter threshold - nothing to split");
        }

        LogSource source = sourceRepository.findFirstByName(target.getSource())
                .orElseThrow(() -> new IllegalArgumentException("Source '" + target.getSource() + "' no longer exists"));

        repository.deleteById(id);
        List<LogTemplate> created = groups.stream().map(group -> buildSplitTemplate(target, matching, group)).toList();
        repository.saveAll(created);
        // Evicts the deleted template and reloads the fresh set (including the new rows just
        // saved above) so the next SearchIndexService pass mines against the post-split cluster
        // set, not a stale in-memory copy still holding the just-deleted template.
        miningService.invalidateCache();
        // The fingerprint gate would otherwise skip this source's files as "unchanged since last
        // pass" until content actually changes - forcing a reindex is what makes the currently
        // indexed lines pick up their new templateId within one scheduled pass instead of waiting
        // for unrelated new content to arrive.
        searchIndexService.forceReindexSource(source);

        log.info("Split template {} ({}) into {} templates", id, target.getTemplateText(), created.size());
        return created.stream().map(TemplateResponse::from).toList();
    }

    private LogTemplate buildSplitTemplate(LogTemplate original, List<LogEntry> matching, TemplateMiningService.ReclusterGroup group) {
        Set<String> memberMessages = new HashSet<>(group.members());
        List<LogEntry> memberEntries = new ArrayList<>(matching.stream().filter(e -> memberMessages.contains(e.message())).toList());

        Instant firstSeenAt = memberEntries.stream().map(LogEntry::timestamp).min(Instant::compareTo).orElseThrow();
        LogEntry latest = memberEntries.stream().max(Comparator.comparing(LogEntry::timestamp)).orElseThrow();

        LogTemplate created = new LogTemplate(original.getSource(), original.getFile(), group.templateText(),
                group.tokenCount(), firstSeenAt, latest.message(), memberEntries.size());
        if (!latest.timestamp().equals(firstSeenAt)) {
            // delta=0: occurrenceCount was already set above from the full recount; this call is
            // only here to roll lastSeenAt/history/sampleRawLine forward to the latest member.
            created.recordOccurrence(latest.timestamp(), latest.message(), 0);
        }
        created.markSplitFrom(original.getTemplateText());
        return created;
    }
}
