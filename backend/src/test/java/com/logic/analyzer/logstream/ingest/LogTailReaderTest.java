package com.logic.analyzer.logstream.ingest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogTailReaderTest {

    @Test
    void returnsAllLinesWhenContentFitsWithinTheByteBudget() throws Exception {
        String content = "line1\nline2\nline3\n";
        TailSource source = fixedTail(content, true);

        List<String> lines = LogTailReader.readLastLines(source, 1024, 100);

        assertThat(lines).containsExactly("line1", "line2", "line3");
    }

    @Test
    void dropsTheLikelyPartialFirstLineWhenNotReadingFromTheStart() throws Exception {
        // Simulates seeking mid-file: the tail window begins partway through a line.
        String content = "artial-line1\nline2\nline3\n";
        TailSource source = fixedTail(content, false);

        List<String> lines = LogTailReader.readLastLines(source, 1024, 100);

        assertThat(lines).containsExactly("line2", "line3");
    }

    @Test
    void capsTheResultToTheRequestedNumberOfLines() throws Exception {
        String content = "l1\nl2\nl3\nl4\nl5\n";
        TailSource source = fixedTail(content, true);

        List<String> lines = LogTailReader.readLastLines(source, 1024, 2);

        assertThat(lines).containsExactly("l4", "l5");
    }

    @Test
    void returnsEmptyListForAnEmptySource() throws Exception {
        TailSource source = fixedTail("", true);

        List<String> lines = LogTailReader.readLastLines(source, 1024, 100);

        assertThat(lines).isEmpty();
    }

    /** A TailSource test double with fixed content; probe() is unused by these tests. */
    private static TailSource fixedTail(String content, boolean fromStart) {
        return new TailSource() {
            @Override
            public TailBytes readTail(int maxBytes) {
                return new TailBytes(content.getBytes(StandardCharsets.UTF_8), fromStart);
            }

            @Override
            public Fingerprint probe() throws IOException {
                throw new UnsupportedOperationException("not used by LogTailReaderTest");
            }
        };
    }
}
