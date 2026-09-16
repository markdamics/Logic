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

    @Column(length = 255)
    private String file;

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

    /** Set only when this row was created by a LOGIC-119 split - a text snapshot rather than the original row's id, since that row is deleted as part of the same split. */
    @Column(length = 2000)
    private String splitFromTemplateText;

    protected LogTemplate() {
        // JPA
    }

    public LogTemplate(String source, String file, String templateText, int tokenCount, Instant seenAt, String sampleRawLine, long occurrenceCount) {
        this.source = source;
        this.file = file;
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

    public String getFile() {
        return file;
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

    public String getSplitFromTemplateText() {
        return splitFromTemplateText;
    }

    /** Called once, right after construction, by TemplateService's split - not part of the constructor since every other caller creates a template with no split lineage at all. */
    public void markSplitFrom(String originalTemplateText) {
        this.splitFromTemplateText = originalTemplateText;
    }
}
