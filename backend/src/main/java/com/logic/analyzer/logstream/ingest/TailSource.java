package com.logic.analyzer.logstream.ingest;

import java.io.IOException;
import java.time.Instant;

/**
 * Something that can hand back the last N bytes of its content without requiring
 * the caller to load the whole thing first - the mechanism that makes tailing a
 * multi-gigabyte file or remote path cheap instead of reading it end to end.
 */
public interface TailSource {

    TailBytes readTail(int maxBytes) throws IOException;

    /**
     * A cheap existence/change probe - just enough metadata (size + last-modified)
     * to tell whether the underlying content has changed, without transferring or
     * parsing any of it. Powers the "new data available" indicator for non-live
     * sources, which aren't re-read automatically.
     */
    Fingerprint probe() throws IOException;

    /**
     * @param fromStart whether this window happens to cover the entire source
     *                  (starts at byte 0) rather than a mid-file tail - callers use
     *                  this to decide whether the first split line is a genuine
     *                  line or a fragment cut off by where the window started.
     */
    record TailBytes(byte[] data, boolean fromStart) {
    }

    /** {@code lastModified} may be null when the source can't report one (e.g. an HTTP server without the header). */
    record Fingerprint(long size, Instant lastModified) {
    }
}
