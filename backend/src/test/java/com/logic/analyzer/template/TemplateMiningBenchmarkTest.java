package com.logic.analyzer.template;

import com.logic.analyzer.logstream.LogEntry;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.SourceType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LOGIC-120: a JMH-lite (plain-JUnit, wall-clock + heap delta) benchmark of
 * {@link TemplateMiningService#mine}'s ingest-path cost under high shape
 * cardinality - the risk flagged but never measured in LOGIC-107. Not part of
 * the regular suite ({@code @Disabled}, since a multi-second timing test has
 * no place slowing down every build or failing on a loaded CI box); run it
 * explicitly (e.g. {@code mvn test -Dtest=TemplateMiningBenchmarkTest#miningPassScalesReasonablyUnderHighShapeCardinality -Dsurefire.failIfNoSpecifiedTests=false}
 * after temporarily removing {@code @Disabled}) to re-measure after a change
 * to the clustering algorithm. See docs/roadmap.md's LOGIC-120 entry for the
 * last recorded results.
 *
 * <p>The synthetic data is deliberately the pathological case: every
 * generated shape has the same token count, so {@code clustersFor()}'s
 * {@code tokenCount} pre-filter (which is the only thing keeping a real,
 * naturally-varied-length source cheap) never eliminates a single candidate -
 * every one of the thousands of distinct-message iterations does a full
 * linear similarity scan over every cluster minted so far. A real source's
 * shapes naturally spread across many token-count buckets, so this is a
 * worse case than production ingest, not a representative one.
 */
class TemplateMiningBenchmarkTest {

    @Test
    @Disabled("Manual perf benchmark, not a correctness test - see class javadoc for how to run it.")
    void miningPassScalesReasonablyUnderHighShapeCardinality() {
        LogTemplateRepository repository = mock(LogTemplateRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findBySourceAndFile(any(), any())).thenReturn(List.of());
        TemplateMiningService service = new TemplateMiningService(repository, 0.5);
        LogSource source = new LogSource("bench", SourceType.LOCAL_FILE, "/var/log/app.log", null, null, null, null);

        int distinctShapes = 2000;
        int occurrencesPerShape = 25;
        List<LogEntry> entries = syntheticEntries(distinctShapes, occurrencesPerShape);

        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
        long start = System.nanoTime();

        service.mine(source, "app.log", entries);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long memoryAfterMb = (runtime.totalMemory() - runtime.freeMemory() - memoryBefore) / (1024 * 1024);

        System.out.printf(
                "LOGIC-120 benchmark: %,d lines / %,d distinct shapes (same token count, worst case) mined in %,d ms (heap delta ~%,d MB)%n",
                entries.size(), distinctShapes, elapsedMs, memoryAfterMb);
    }

    /**
     * {@code occurrencesPerShape} raw variations per shape (a different duration each time, which
     * masks away to the same token) so mine() does real tokenize+match work for every one of
     * them, not just {@code distinctShapes} total calls - matching how a real reindex pass sees
     * every raw line, not a pre-deduplicated set.
     */
    private static List<LogEntry> syntheticEntries(int distinctShapes, int occurrencesPerShape) {
        List<LogEntry> entries = new ArrayList<>(distinctShapes * occurrencesPerShape);
        Instant now = Instant.now();
        for (int shape = 0; shape < distinctShapes; shape++) {
            String operation = letterCode(shape);
            for (int occ = 0; occ < occurrencesPerShape; occ++) {
                String message = operation + " operation completed in " + (100 + occ) + " milliseconds";
                entries.add(new LogEntry(0, now, LogLevel.INFO, "bench", "app.log", message));
            }
        }
        return entries;
    }

    /** Base-26 letter encoding (0->"a", 26->"ba", ...) - a pure-alphabetic token per shape that TemplateTokenizer's masking regexes never touch, so it stays a distinct literal across all 2000 shapes. */
    private static String letterCode(int n) {
        StringBuilder sb = new StringBuilder();
        do {
            sb.append((char) ('a' + (n % 26)));
            n /= 26;
        } while (n > 0);
        return sb.reverse().toString();
    }
}
