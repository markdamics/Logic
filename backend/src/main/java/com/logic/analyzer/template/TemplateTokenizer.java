package com.logic.analyzer.template;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a message into whitespace-delimited tokens and masks the
 * high-cardinality ones (numbers, UUIDs, IPs, hex blobs, quoted strings,
 * mixed alphanumeric identifiers) to a fixed placeholder, so two lines that
 * only differ in an id/count/address compare as identical token-for-token
 * before {@link TemplateMiningService} even needs its similarity/wildcard
 * merge step. Timestamps are deliberately not handled here: LogLineParser
 * already strips a line's leading timestamp into LogEntry.timestamp() before
 * this ever runs (see stripParsed), and MessageFieldExtractor pulls a
 * structured format's timestamp field out separately - so surviving message
 * text essentially never starts with one.
 */
public final class TemplateTokenizer {

    static final String WILDCARD = "*";

    private static final Pattern UUID = Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$");
    private static final Pattern NUMBER = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");
    private static final Pattern HEX = Pattern.compile("(?i)^[0-9a-f]{8,}$");
    private static final Pattern QUOTED = Pattern.compile("^([\"']).*\\1$");
    private static final Pattern MIXED_ALNUM_ID = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9_-]{2,}$");

    private TemplateTokenizer() {
    }

    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] rawTokens = text.trim().split("\\s+");
        List<String> masked = new ArrayList<>(rawTokens.length);
        for (String token : rawTokens) {
            masked.add(mask(token));
        }
        return masked;
    }

    private static String mask(String token) {
        if (UUID.matcher(token).matches()) return "<UUID>";
        if (IPV4.matcher(token).matches()) return "<IP>";
        if (NUMBER.matcher(token).matches()) return "<NUM>";
        if (token.length() >= 8 && HEX.matcher(token).matches()) return "<HEX>";
        if (token.length() >= 2 && QUOTED.matcher(token).matches()) return "<STR>";
        if (MIXED_ALNUM_ID.matcher(token).matches()) return "<ID>";
        return token;
    }
}
