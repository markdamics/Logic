package com.logic.analyzer.template;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A clustered "shape" of log message mined by {@link TemplateMiningService} -
 * see that class for the tokenize/mask/merge algorithm. {@code templateText}
 * is a space-joined sequence of masked tokens and "*" wildcards (positions
 * that have been observed to vary across occurrences); {@code tokenCount}
 * mirrors its length and is stored separately purely so the mining service
 * can bucket candidate lines by token count without re-splitting templateText
 * on every comparison.
 *
 * {@code source} is a name copy (not a foreign key), matching {@link
 * com.logic.analyzer.redaction.RedactionRule}'s scoping convention - unlike
 * that entity, every template is scoped to exactly one source (there is no
 * global bucket), since a template tree is inherently per-source.
 *
 * {@code history}/{@code historyBucketStartMillis} back the "trend
 * sparkline" the roadmap calls for: a fixed-length ring of per-minute
 * occurrence counts, oldest first, rolled forward incrementally in {@link
 * #recordOccurrence} rather than computed from a full occurrence log (which
 * this entity never keeps - only a running count and one recent sample line).
 */
@Entity
@Table(name = "log_template")
public class LogTemplate {

    static final long HISTORY_BUCKET_MILLIS = 60_000L; // 1 minute
    static final int HISTORY_LENGTH = 20; // 20 minutes of trend

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false, length = 2000)
    private String templateText;

    @Column(nullable = false)
    private int tokenCount;

    @Column(nullable = false)
    private long occurrenceCount;

    @Column(nullable = false)
    private Instant firstSeenAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    @Column(length = 2000)
    private String sampleRawLine;

    @Column(length = 500)
    private String history;

    private Long historyBucketStartMillis;

    protected LogTemplate() {
        // JPA
    }

    public LogTemplate(String source, String templateText, int tokenCount, Instant seenAt, String sampleRawLine, long occurrenceCount) {
        this.source = source;
        this.templateText = templateText;
        this.tokenCount = tokenCount;
        this.occurrenceCount = occurrenceCount;
        this.firstSeenAt = seenAt;
        this.lastSeenAt = seenAt;
        this.sampleRawLine = sampleRawLine;
        this.history = String.valueOf(occurrenceCount);
        this.historyBucketStartMillis = floorToBucket(seenAt);
    }

    /** Widens the merged template text after a new occurrence generalizes another token position to a wildcard. */
    public void updateTemplateText(String templateText) {
        this.templateText = templateText;
    }

    /** @param delta how many new occurrences this represents (see TemplateMiningService for why it's a delta, not always 1). */
    public void recordOccurrence(Instant seenAt, String rawLine, long delta) {
        occurrenceCount += delta;
        if (seenAt.isAfter(lastSeenAt)) {
            lastSeenAt = seenAt;
        }
        if (seenAt.isBefore(firstSeenAt)) {
            firstSeenAt = seenAt;
        }
        sampleRawLine = rawLine;
        rollHistory(seenAt, delta);
    }

    private void rollHistory(Instant seenAt, long delta) {
        long bucket = floorToBucket(seenAt);
        List<Long> counts = parseHistory();
        if (bucket == historyBucketStartMillis) {
            counts.set(counts.size() - 1, counts.get(counts.size() - 1) + delta);
        } else if (bucket > historyBucketStartMillis) {
            long gap = (bucket - historyBucketStartMillis) / HISTORY_BUCKET_MILLIS;
            for (long i = 1; i < gap; i++) {
                counts.add(0L);
            }
            counts.add(delta);
            while (counts.size() > HISTORY_LENGTH) {
                counts.remove(0);
            }
            historyBucketStartMillis = bucket;
        } else {
            // Arrived out of order, older than the oldest bucket still tracked - fold it
            // into that bucket rather than trying to insert a bucket behind the window.
            counts.set(0, counts.get(0) + delta);
        }
        this.history = counts.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private List<Long> parseHistory() {
        List<Long> counts = new ArrayList<>();
        for (String part : history.split(",")) {
            counts.add(Long.parseLong(part));
        }
        return counts;
    }

    private static long floorToBucket(Instant instant) {
        long millis = instant.toEpochMilli();
        return millis - (millis % HISTORY_BUCKET_MILLIS);
    }

    public Long getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getTemplateText() {
        return templateText;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public long getOccurrenceCount() {
        return occurrenceCount;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public String getSampleRawLine() {
        return sampleRawLine;
    }

    public List<Long> getHistoryCounts() {
        return parseHistory();
    }
}
