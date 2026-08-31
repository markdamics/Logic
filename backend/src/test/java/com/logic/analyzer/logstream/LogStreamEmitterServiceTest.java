package com.logic.analyzer.logstream;

import com.logic.analyzer.logstream.dto.LogQueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifies the dedup contract that makes the SSE stream safe to poll
 * repeatedly: a subscriber never gets sent an entry it's already seen (either
 * as part of the connect-time baseline, or from an earlier poll), and only
 * genuinely new entries go out on each poll. Uses a capturing SseEmitter
 * subclass (via the service's newEmitter() seam) instead of a real,
 * transport-backed one, since a bare SseEmitter has nothing listening until
 * a real request initializes it.
 */
@ExtendWith(MockitoExtension.class)
class LogStreamEmitterServiceTest {

    @Mock
    private LogQueryService queryService;

    private TestableEmitterService service;

    @BeforeEach
    void setUp() {
        service = new TestableEmitterService(queryService);
    }

    private static LogEntry entry(long secondsAgo, String message) {
        return new LogEntry(0, Instant.now().minusSeconds(secondsAgo), LogLevel.INFO, "source-a", "app.log", message);
    }

    private static LogQueryResult resultOf(LogEntry... entries) {
        List<LogEntry> content = List.of(entries);
        return new LogQueryResult(content, 0, 200, content.size(), 1, null);
    }

    @Test
    void baselineIsPrimedWithoutSendingAnything() {
        when(queryService.query(any())).thenReturn(resultOf(entry(10, "already here")));

        SseEmitter emitter = service.subscribe(null, Set.of(), null, null);
        service.pollAll();

        assertThat(service.emitters).hasSize(1);
        assertThat(((CapturingSseEmitter) service.emitters.get(0)).sentBatches).isEmpty();
        assertThat(emitter).isNotNull();
    }

    @Test
    void onlySendsEntriesNotSeenBefore() {
        LogEntry existing = entry(10, "already here");
        LogEntry fresh = entry(1, "brand new");

        when(queryService.query(any())).thenReturn(resultOf(existing));
        service.subscribe(null, Set.of(), null, null);

        when(queryService.query(any())).thenReturn(resultOf(fresh, existing));
        service.pollAll();

        CapturingSseEmitter emitter = (CapturingSseEmitter) service.emitters.get(0);
        assertThat(emitter.sentBatches).hasSize(1);
        assertThat(emitter.sentBatches.get(0)).extracting(LogEntry::message).containsExactly("brand new");
    }

    @Test
    void anUnchangedEntryWithAnUnstableFallbackTimestampIsNotResent() {
        // LogLineParser stamps ingestion time ("now") on any line whose format it can't
        // parse a timestamp from - re-reading the same unchanged line on a later poll can
        // therefore come back with a *different* LogEntry.timestamp even though nothing
        // about the underlying log line changed. Dedup must not be fooled by that.
        LogEntry firstRead = new LogEntry(0, Instant.now(), LogLevel.INFO, "source-a", "app.log", "app started");
        when(queryService.query(any())).thenReturn(resultOf(firstRead));
        service.subscribe(null, Set.of(), null, null);

        LogEntry rereadWithNewTimestamp = new LogEntry(0, Instant.now().plusSeconds(5), LogLevel.INFO, "source-a", "app.log", "app started");
        when(queryService.query(any())).thenReturn(resultOf(rereadWithNewTimestamp));
        service.pollAll();

        CapturingSseEmitter emitter = (CapturingSseEmitter) service.emitters.get(0);
        assertThat(emitter.sentBatches).isEmpty();
    }

    @Test
    void emitsResyncInsteadOfDeltaWhenTheTotalMatchCountDrops() {
        // A line getting edited/deleted out from under a tailed file (or the file being
        // rewritten/truncated) means the total match count drops even though there's
        // nothing to add to "newEntries" - there's no cheap way to know which previously-
        // pushed rows are now stale, so this must trigger a full client-side refetch
        // ("resync") rather than silently doing nothing.
        LogEntry first = entry(10, "first");
        LogEntry second = entry(5, "second");
        when(queryService.query(any())).thenReturn(resultOf(second, first));
        service.subscribe(null, Set.of(), null, null);

        when(queryService.query(any())).thenReturn(resultOf(first));
        service.pollAll();

        CapturingSseEmitter emitter = (CapturingSseEmitter) service.emitters.get(0);
        assertThat(emitter.sentBatches).isEmpty();
        assertThat(emitter.sentEventNames).contains("resync");
    }

    @Test
    void repollingWithNoChangesSendsNothingMore() {
        LogEntry existing = entry(10, "already here");
        when(queryService.query(any())).thenReturn(resultOf(existing));

        service.subscribe(null, Set.of(), null, null);
        service.pollAll();
        service.pollAll();

        CapturingSseEmitter emitter = (CapturingSseEmitter) service.emitters.get(0);
        assertThat(emitter.sentBatches).isEmpty();
    }

    private static class TestableEmitterService extends LogStreamEmitterService {
        final List<SseEmitter> emitters = new ArrayList<>();

        TestableEmitterService(LogQueryService queryService) {
            super(queryService);
        }

        @Override
        protected SseEmitter newEmitter() {
            CapturingSseEmitter emitter = new CapturingSseEmitter();
            emitters.add(emitter);
            return emitter;
        }
    }

    private static class CapturingSseEmitter extends SseEmitter {
        final List<List<LogEntry>> sentBatches = new ArrayList<>();
        final List<String> sentEventNames = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            for (ResponseBodyEmitter.DataWithMediaType data : builder.build()) {
                if (data.getData() instanceof List<?> list) {
                    @SuppressWarnings("unchecked")
                    List<LogEntry> entries = (List<LogEntry>) list;
                    sentBatches.add(entries);
                } else if (data.getData() instanceof String raw && raw.startsWith("event:")) {
                    sentEventNames.add(raw.substring("event:".length()).split("\\r?\\n", 2)[0]);
                }
            }
        }
    }
}
