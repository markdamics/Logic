package com.logic.analyzer.template;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a message into delimited tokens and masks the high-cardinality ones
 * (numbers, UUIDs, IPs, hex blobs, quoted strings, emails, dates, mixed
 * alphanumeric identifiers) to a fixed placeholder, so two lines that only
 * differ in an id/count/address compare as identical token-for-token before
 * {@link TemplateMiningService} even needs its similarity/wildcard merge
 * step.
 *
 * Delimiting on whitespace <em>and</em> commas (not just whitespace) matters
 * for comma-delimited content (a CSV/TSV-shaped log line, or just a sentence
 * with "word, word" punctuation) - without it, an entire unspaced row like
 * "1001,Liu,Brown,liu@example.com,BR,pro" is one giant token that never
 * masks or matches another row, so every row mints its own template instead
 * of clustering into one.
 *
 * Leading record timestamps are deliberately not handled here: LogLineParser
 * already strips those into LogEntry.timestamp() before this ever runs (see
 * stripParsed), and MessageFieldExtractor pulls a structured format's
 * timestamp field out separately. The DATE pattern below instead covers
 * dates/timestamps that show up as a field *within* the message body (e.g.
 * a CSV "signup_date" column), which those two don't touch.
 */
public final class TemplateTokenizer {

    static final String WILDCARD = "*";

    private static final Pattern UUID = Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$");
    private static final Pattern NUMBER = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");
    private static final Pattern HEX = Pattern.compile("(?i)^[0-9a-f]{8,}$");
    private static final Pattern QUOTED = Pattern.compile("^([\"']).*\\1$");
    private static final Pattern EMAIL = Pattern.compile("(?i)^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?Z?)?$");
    private static final Pattern MIXED_ALNUM_ID = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9_-]{2,}$");

    private TemplateTokenizer() {
    }

    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] rawTokens = text.trim().split("[\\s,]+");
        List<String> masked = new ArrayList<>(rawTokens.length);
        for (String token : rawTokens) {
            if (token.isEmpty()) {
                continue; // a leading comma/space produces an empty leading split segment
            }
            masked.add(mask(token));
        }
        return masked;
    }

    private static String mask(String token) {
        if (UUID.matcher(token).matches()) return "<UUID>";
        if (IPV4.matcher(token).matches()) return "<IP>";
        if (DATE.matcher(token).matches()) return "<DATE>";
        if (NUMBER.matcher(token).matches()) return "<NUM>";
        if (EMAIL.matcher(token).matches()) return "<EMAIL>";
        if (token.length() >= 8 && HEX.matcher(token).matches()) return "<HEX>";
        if (token.length() >= 2 && QUOTED.matcher(token).matches()) return "<STR>";
        if (MIXED_ALNUM_ID.matcher(token).matches()) return "<ID>";
        return token;
    }
}
