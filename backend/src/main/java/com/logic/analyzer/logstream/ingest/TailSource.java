package com.logic.analyzer.logstream.ingest;

import java.io.IOException;

/**
 * Something that can hand back the last N bytes of its content without requiring
 * the caller to load the whole thing first - the mechanism that makes tailing a
 * multi-gigabyte file or remote path cheap instead of reading it end to end.
 */
public interface TailSource {

    TailBytes readTail(int maxBytes) throws IOException;

    /**
     * @param fromStart whether this window happens to cover the entire source
     *                  (starts at byte 0) rather than a mid-file tail - callers use
     *                  this to decide whether the first split line is a genuine
     *                  line or a fragment cut off by where the window started.
     */
    record TailBytes(byte[] data, boolean fromStart) {
    }
}
