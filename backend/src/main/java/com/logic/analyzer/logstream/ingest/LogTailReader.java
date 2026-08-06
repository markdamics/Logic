package com.logic.analyzer.logstream.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class LogTailReader {

    private LogTailReader() {
    }

    /**
     * Reads up to the last maxBytes of a source and returns up to maxLines of its
     * most recent lines. Bounding by bytes first keeps this cheap even against
     * multi-gigabyte files or remote paths, since only a small trailing window is
     * ever transferred or held in memory - that's the whole point of tailing
     * instead of loading everything.
     */
    public static List<String> readLastLines(TailSource source, int maxBytes, int maxLines) throws IOException {
        TailSource.TailBytes tail = source.readTail(maxBytes);
        if (tail.data().length == 0) {
            return List.of();
        }

        String text = new String(tail.data(), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));

        if (!tail.fromStart() && !lines.isEmpty()) {
            lines.remove(0); // likely a partial line, since we started reading mid-file
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1); // trailing newline yields an empty final element
        }
        if (lines.size() > maxLines) {
            lines = lines.subList(lines.size() - maxLines, lines.size());
        }
        return lines;
    }
}
