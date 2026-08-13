package com.logic.analyzer.logstream.ingest;

import com.logic.analyzer.logstream.LogLevel;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort parser for arbitrary log line formats: detects a level and a
 * timestamp using a handful of common patterns (bracketed level tags, this
 * project's own app-log timestamp format, ISO-8601, Apache/Combined
 * access-log style, and BSD/RFC-3164 syslog style), falling back to INFO /
 * "now" when nothing matches. Lines that start with whitespace are treated as
 * continuations (e.g. stack trace frames) of the previous entry rather than
 * new ones.
 */
public final class LogLineParser {

    private static final Pattern BRACKET_LEVEL = Pattern.compile("(?i)\\[(ERROR|WARN|WARNING|INFO|DEBUG|TRACE)]");
    private static final Pattern BARE_LEVEL = Pattern.compile("(?i)\\b(ERROR|WARN|WARNING|INFO|DEBUG|TRACE)\\b");
    private static final Pattern HTTP_STATUS = Pattern.compile("\"\\s+(\\d{3})\\s");
    // Last-resort severity signal for formats (like bare syslog) that carry no
    // explicit level tag at all - e.g. sshd's "Failed password for ..." or
    // nginx's "worker process ... exited on signal 11".
    private static final Pattern ERROR_KEYWORDS = Pattern.compile(
            "(?i)\\b(exception|panic|fatal|critical|segfault|denied|exited on signal|out of memory)\\b");
    private static final Pattern WARN_KEYWORDS = Pattern.compile(
            "(?i)\\b(fail(?:ed|ure)?|invalid|timeout|unauthorized|refused|retry(?:ing)?|degraded)\\b");

    private static final Pattern APP_TS = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3})");
    private static final Pattern ACCESS_TS = Pattern.compile("\\[(\\d{2}/[A-Za-z]{3}/\\d{4}:\\d{2}:\\d{2}:\\d{2} [+-]\\d{4})]");
    private static final Pattern ISO_TS = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)(Z|[+-]\\d{2}:?\\d{2})?");
    // BSD/RFC-3164 syslog: "Aug  6 08:05:58 ..." - no year, and the day is
    // space-padded rather than zero-padded (so a single space or two).
    private static final Pattern SYSLOG_TS = Pattern.compile("^([A-Za-z]{3}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2})");

    private static final DateTimeFormatter APP_LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");
    private static final DateTimeFormatter ACCESS_LOG_FORMAT = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);
    private static final DateTimeFormatter SYSLOG_LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy MMM d HH:mm:ss", Locale.ENGLISH);

    private LogLineParser() {
    }

    public static List<ParsedLine> parse(List<String> rawLines) {
        List<ParsedLine> result = new ArrayList<>();
        StringBuilder message = null;
        LogLevel level = null;
        Instant timestamp = null;

        for (String line : rawLines) {
            if (line.isEmpty()) {
                continue;
            }
            boolean continuation = message != null && (line.charAt(0) == ' ' || line.charAt(0) == '\t');
            if (continuation) {
                message.append('\n').append(line);
                continue;
            }
            if (message != null) {
                result.add(new ParsedLine(timestamp, level, message.toString()));
            }
            timestamp = detectTimestamp(line);
            level = detectLevel(line);
            message = new StringBuilder(stripParsed(line));
        }
        if (message != null) {
            result.add(new ParsedLine(timestamp, level, message.toString()));
        }
        return result;
    }

    /**
     * Removes whichever timestamp span (if any) and bracketed level tag (if
     * any) were matched during detection, leaving whatever wasn't recognized -
     * this becomes the entry's message. Only the bracketed level form is
     * stripped (not the bare-word form), since removing a bare match risks
     * mangling ordinary sentence text that happens to contain a level-like
     * word.
     */
    private static String stripParsed(String line) {
        String stripped = line;
        if (APP_TS.matcher(line).find()) {
            stripped = removeFirstMatch(stripped, APP_TS);
        } else if (ACCESS_TS.matcher(line).find()) {
            stripped = removeFirstMatch(stripped, ACCESS_TS);
        } else if (ISO_TS.matcher(line).find()) {
            stripped = removeFirstMatch(stripped, ISO_TS);
        } else if (SYSLOG_TS.matcher(line).find()) {
            stripped = removeFirstMatch(stripped, SYSLOG_TS);
        }
        stripped = removeFirstMatch(stripped, BRACKET_LEVEL);
        return stripped.strip().replaceAll(" {2,}", " ");
    }

    private static String removeFirstMatch(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return text;
        }
        int start = m.start();
        int end = m.end();
        if (end < text.length() && text.charAt(end) == ' ') {
            end++;
        }
        return text.substring(0, start) + text.substring(end);
    }

    private static LogLevel detectLevel(String line) {
        Matcher m = BRACKET_LEVEL.matcher(line);
        if (m.find()) {
            return mapLevel(m.group(1));
        }
        String head = line.length() > 80 ? line.substring(0, 80) : line;
        m = BARE_LEVEL.matcher(head);
        if (m.find()) {
            return mapLevel(m.group(1));
        }
        m = HTTP_STATUS.matcher(line);
        if (m.find()) {
            int status = Integer.parseInt(m.group(1));
            if (status >= 500) return LogLevel.ERROR;
            if (status >= 400) return LogLevel.WARN;
            return LogLevel.INFO;
        }
        if (ERROR_KEYWORDS.matcher(line).find()) {
            return LogLevel.ERROR;
        }
        if (WARN_KEYWORDS.matcher(line).find()) {
            return LogLevel.WARN;
        }
        return LogLevel.INFO;
    }

    private static LogLevel mapLevel(String token) {
        return switch (token.toUpperCase(Locale.ROOT)) {
            case "ERROR" -> LogLevel.ERROR;
            case "WARN", "WARNING" -> LogLevel.WARN;
            case "DEBUG", "TRACE" -> LogLevel.DEBUG;
            default -> LogLevel.INFO;
        };
    }

    private static Instant detectTimestamp(String line) {
        Matcher m = APP_TS.matcher(line);
        if (m.find()) {
            try {
                return LocalDateTime.parse(m.group(1), APP_LOG_FORMAT).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                // fall through to the next pattern
            }
        }
        m = ACCESS_TS.matcher(line);
        if (m.find()) {
            try {
                return ZonedDateTime.parse(m.group(1), ACCESS_LOG_FORMAT).toInstant();
            } catch (DateTimeParseException ignored) {
                // fall through to the next pattern
            }
        }
        m = ISO_TS.matcher(line);
        if (m.find()) {
            try {
                String offset = m.group(2) == null ? "Z" : m.group(2);
                return OffsetDateTime.parse(m.group(1) + offset, DateTimeFormatter.ISO_DATE_TIME).toInstant();
            } catch (DateTimeParseException ignored) {
                // fall through to the next pattern
            }
        }
        m = SYSLOG_TS.matcher(line);
        if (m.find()) {
            try {
                return parseSyslogTimestamp(m.group(1));
            } catch (DateTimeParseException ignored) {
                // fall through to the default below
            }
        }
        return Instant.now();
    }

    /**
     * BSD syslog timestamps omit the year, so it's assumed to be the current
     * one; if that puts the timestamp more than a day in the future (e.g.
     * reading a December entry from a file while it's now early January),
     * the year is rolled back by one instead.
     */
    private static Instant parseSyslogTimestamp(String raw) {
        String normalized = raw.trim().replaceAll("\\s+", " ");
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        LocalDateTime dt = LocalDateTime.parse(year + " " + normalized, SYSLOG_LOG_FORMAT);
        Instant instant = dt.toInstant(ZoneOffset.UTC);
        if (instant.isAfter(Instant.now().plus(Duration.ofDays(1)))) {
            instant = dt.minusYears(1).toInstant(ZoneOffset.UTC);
        }
        return instant;
    }

    public record ParsedLine(Instant timestamp, LogLevel level, String message) {
    }
}
