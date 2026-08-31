package com.logic.analyzer.logstream;

import com.logic.analyzer.logstream.dto.LogQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bridges the existing poll-based query pipeline (LogQueryService, backed by
 * the periodically-reindexed Lucene index - see SearchIndexService) to a
 * push-based SSE stream, so the frontend no longer has to blind-poll on a
 * fixed timer to notice new log lines. This still polls under the hood
 * (there's no event bus back to the tail readers - SFTP/HTTP/local files are
 * pull sources by nature), but the polling happens once here, server-side, on
 * a much tighter interval than the old client timer, and only actually
 * matching *new* entries are ever sent to the client. If the total match
 * count ever drops (a line was edited/deleted out from under a tailed file,
 * or the underlying file got rewritten/truncated), a "resync" event tells the
 * client to refetch instead - there's no cheap way to know which previously-
 * pushed rows are now stale, so the client's buffer just gets replaced rather
 * than patched.
 */
@Service
public class LogStreamEmitterService {

    private static final Logger log = LoggerFactory.getLogger(LogStreamEmitterService.class);

    /** How many of the most-recent matching entries each poll looks at for new arrivals. */
    private static final int POLL_WINDOW_SIZE = 200;
    /** Caps how many signatures a single subscription remembers, so a long-lived connection can't grow this unbounded. */
    private static final int MAX_SEEN_SIGNATURES = 5000;

    private final LogQueryService queryService;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    public LogStreamEmitterService(LogQueryService queryService) {
        this.queryService = queryService;
    }

    public SseEmitter subscribe(String search, Set<LogLevel> levels, String source, String file) {
        LogQueryParams params = new LogQueryParams(search, levels, source, file, 0, "time", "desc", 0, POLL_WINDOW_SIZE);
        SseEmitter emitter = newEmitter();
        Subscription subscription = new Subscription(emitter, params);

        // Baseline: mark everything currently matching as already-seen without sending it -
        // the client just loaded this same window via GET /api/logs, so resending it here
        // would only duplicate what's already on screen.
        try {
            LogQueryResult baseline = queryService.query(params);
            baseline.content().forEach(entry -> subscription.seen.add(signatureOf(entry)));
            subscription.lastTotalElements = baseline.totalElements();
        } catch (Exception e) {
            log.warn("Failed to prime SSE stream baseline: {}", e.getMessage());
        }

        subscriptions.add(subscription);
        emitter.onCompletion(() -> subscriptions.remove(subscription));
        emitter.onTimeout(() -> subscriptions.remove(subscription));
        emitter.onError(e -> subscriptions.remove(subscription));
        log.info("SSE live-tail subscriber connected ({} active)", subscriptions.size());
        return emitter;
    }

    /** Seam for tests to substitute a capturing SseEmitter instead of a real, transport-backed one. */
    protected SseEmitter newEmitter() {
        return new SseEmitter(0L);
    }

    @Scheduled(fixedDelayString = "${app.logstream.sse-poll-interval-ms:1000}")
    void pollAll() {
        for (Subscription subscription : subscriptions) {
            poll(subscription);
        }
    }

    private void poll(Subscription subscription) {
        LogQueryResult result;
        try {
            result = queryService.query(subscription.params);
        } catch (Exception e) {
            log.warn("SSE live-tail poll failed: {}", e.getMessage());
            return;
        }

        // result.content() is sorted newest-first; keep that order so the client can
        // simply prepend the whole batch ahead of what it's already showing.
        List<LogEntry> newEntries = result.content().stream()
                .filter(entry -> !subscription.seen.contains(signatureOf(entry)))
                .toList();

        for (LogEntry entry : result.content()) {
            markSeen(subscription, signatureOf(entry));
        }

        // A drop in the total match count means something that used to match got edited
        // or deleted out from under us - lines disappearing or a file getting rewritten/
        // truncated, not just new ones arriving. There's no cheap way to know *which*
        // previously-pushed rows are now stale, so rather than try to patch the client's
        // buffer, tell it to throw the buffer away and refetch for real.
        boolean shrank = result.totalElements() < subscription.lastTotalElements;
        subscription.lastTotalElements = result.totalElements();

        try {
            if (shrank) {
                subscription.emitter.send(SseEmitter.event().name("resync"));
            } else if (!newEntries.isEmpty()) {
                subscription.emitter.send(SseEmitter.event().name("logs").data(newEntries));
            }
        } catch (IOException e) {
            // The connection is gone; the emitter's own onError/onCompletion callback
            // handles deregistration, so just stop trying to write to it here.
            log.debug("SSE live-tail send failed, dropping subscriber: {}", e.getMessage());
        }
    }

    private void markSeen(Subscription subscription, EntrySignature signature) {
        Set<EntrySignature> seen = subscription.seen;
        if (seen.add(signature) && seen.size() > MAX_SEEN_SIGNATURES) {
            Iterator<EntrySignature> it = seen.iterator();
            it.next();
            it.remove();
        }
    }

    /**
     * Identity for dedup purposes - deliberately excludes timestamp. LogEntry.id is
     * reassigned fresh on every query, so it's never a stable key across polls; and
     * LogLineParser falls back to ingestion time ("now") for any line whose format it
     * doesn't recognize, which would otherwise change on every single poll and make an
     * unchanged line look "new" forever. The tradeoff is that two genuinely distinct but
     * textually identical lines from a source with no parseable per-line timestamp can
     * collapse into a single push - an acceptable, narrow edge case next to guaranteed
     * duplicate flooding for any format LogLineParser can't timestamp.
     */
    private EntrySignature signatureOf(LogEntry entry) {
        return new EntrySignature(entry.source(), entry.file(), entry.message());
    }

    private record EntrySignature(String source, String file, String message) {
    }

    private static final class Subscription {
        final SseEmitter emitter;
        final LogQueryParams params;
        final Set<EntrySignature> seen = new LinkedHashSet<>();
        int lastTotalElements = 0;

        Subscription(SseEmitter emitter, LogQueryParams params) {
            this.emitter = emitter;
            this.params = params;
        }
    }
}
