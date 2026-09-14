package com.logic.analyzer.template;

import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.search.extract.MessageFieldExtractor;
import com.logic.analyzer.source.LogSource;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Incremental, streaming, Drain-inspired template miner: tokenizes/masks
 * each message (see {@link TemplateTokenizer}), compares it against the
 * existing per-source templates of the same token count, and either folds it
 * into the closest match (widening any differing position to a wildcard) or
 * mints a new template. Deliberately not a full grok/ML engine - length
 * bucketing plus a flat similarity threshold, same simplification Drain
 * itself makes for streaming use.
 *
 * Called once per (source, file) from {@link com.logic.analyzer.search.index.SearchIndexService},
 * which re-reads and re-adds a file's *entire* current tail window on every
 * pass its fingerprint changes (not just newly appended lines - see that
 * class's own comment). Counting every entry in that list directly would
 * inflate occurrenceCount by the whole window's size on every append instead
 * of by one, but a plain "have I seen this exact line before" dedup over-
 * corrects the other way: it would collapse genuinely repeated identical
 * lines (e.g. health-check spam) down to a single occurrence forever. Instead
 * {@link #mine} compares this pass's count of each distinct message text
 * against the previous pass's for the same file, and only mines the positive
 * delta - the actual number of new occurrences either way.
 */
@Service
public class TemplateMiningService {

    private static final double SIMILARITY_THRESHOLD = 0.5;

    private final LogTemplateRepository repository;
    private final Map<String, List<LogTemplate>> clustersBySource = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> lastPassMessageCounts = new ConcurrentHashMap<>();

    public TemplateMiningService(LogTemplateRepository repository) {
        this.repository = repository;
    }

    public synchronized void mine(LogSource source, String file, List<LogEntry> entries) {
        Map<String, Long> countsThisPass = new LinkedHashMap<>();
        Map<String, Instant> latestTimestamp = new HashMap<>();
        for (LogEntry entry : entries) {
            countsThisPass.merge(entry.message(), 1L, Long::sum);
            latestTimestamp.merge(entry.message(), entry.timestamp(), (a, b) -> b.isAfter(a) ? b : a);
        }

        Map<String, Long> previousCounts = lastPassMessageCounts.computeIfAbsent(fileKey(source, file), k -> new HashMap<>());
        for (Map.Entry<String, Long> entry : countsThisPass.entrySet()) {
            String message = entry.getKey();
            long delta = entry.getValue() - previousCounts.getOrDefault(message, 0L);
            if (delta > 0) {
                mineOccurrences(source, message, latestTimestamp.get(message), delta);
            }
        }
        previousCounts.clear();
        previousCounts.putAll(countsThisPass);
    }

    /** A file that disappeared (rotated away, deleted) should start fresh if a same-named file ever reappears. */
    public void forgetFile(LogSource source, String file) {
        lastPassMessageCounts.remove(fileKey(source, file));
    }

    /** Called when a source itself is deleted, mirroring SearchIndexService#purgeSource's fingerprint cleanup. */
    public void forgetSource(LogSource source) {
        String prefix = source.getId() + "|";
        lastPassMessageCounts.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Forces the next mine() for any source to reload its templates from the repository. */
    public void invalidateCache() {
        clustersBySource.clear();
    }

    private static String fileKey(LogSource source, String file) {
        return source.getId() + "|" + (file == null ? "" : file);
    }

    private void mineOccurrences(LogSource source, String message, Instant seenAt, long delta) {
        String text = messageTextToMine(message);
        List<String> tokens = TemplateTokenizer.tokenize(text);
        if (tokens.isEmpty()) {
            return;
        }

        List<LogTemplate> clusters = clustersFor(source.getName());
        LogTemplate best = null;
        double bestSimilarity = 0;
        for (LogTemplate candidate : clusters) {
            if (candidate.getTokenCount() != tokens.size()) {
                continue;
            }
            double similarity = similarity(splitTemplateText(candidate.getTemplateText()), tokens);
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                best = candidate;
            }
        }

        if (best != null && bestSimilarity >= SIMILARITY_THRESHOLD) {
            best.updateTemplateText(String.join(" ", merge(splitTemplateText(best.getTemplateText()), tokens)));
            best.recordOccurrence(seenAt, message, delta);
            repository.save(best);
        } else {
            LogTemplate created = new LogTemplate(
                    source.getName(), String.join(" ", tokens), tokens.size(), seenAt, message, delta);
            repository.save(created);
            clusters.add(created);
        }
    }

    private List<LogTemplate> clustersFor(String sourceName) {
        return clustersBySource.computeIfAbsent(sourceName, name -> new ArrayList<>(repository.findBySource(name)));
    }

    /** Mines the structured "message" remainder when a format was already detected, instead of re-deriving it. */
    private String messageTextToMine(String message) {
        MessageFieldExtractor.ExtractedFields extracted = MessageFieldExtractor.extract(message);
        for (MessageFieldExtractor.Field field : extracted.fields()) {
            if (field.name().equals("message")) {
                return field.value();
            }
        }
        return message;
    }

    private static List<String> splitTemplateText(String templateText) {
        return Arrays.asList(templateText.split(" "));
    }

    private static double similarity(List<String> templateTokens, List<String> candidateTokens) {
        int matches = 0;
        for (int i = 0; i < templateTokens.size(); i++) {
            String templateToken = templateTokens.get(i);
            if (templateToken.equals(TemplateTokenizer.WILDCARD) || templateToken.equals(candidateTokens.get(i))) {
                matches++;
            }
        }
        return (double) matches / templateTokens.size();
    }

    private static List<String> merge(List<String> templateTokens, List<String> candidateTokens) {
        List<String> merged = new ArrayList<>(templateTokens.size());
        for (int i = 0; i < templateTokens.size(); i++) {
            String templateToken = templateTokens.get(i);
            boolean sameOrWildcard = templateToken.equals(TemplateTokenizer.WILDCARD) || templateToken.equals(candidateTokens.get(i));
            merged.add(sameOrWildcard ? templateToken : TemplateTokenizer.WILDCARD);
        }
        return merged;
    }
}
