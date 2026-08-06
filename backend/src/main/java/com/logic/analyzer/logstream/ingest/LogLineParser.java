package com.logic.analyzer.logstream.ingest;

import com.logic.analyzer.logstream.LogLevel;

import java.time.Instant;
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
 * project's own app-log timestamp format, ISO-8601, and Apache/Combined
 * access-log style), falling back to INFO / "now" when nothing matches. Lines
 * that start with whitespace are treated as continuations (e.g. stack trace
 * frames) of the previous entry rather than new ones.
 */
public final class LogLineParser {

    private static final Pattern BRACKET_LEVEL = Pattern.compile("(?i)\\[(ERROR|WARN|WARNING|INFO|DEBUG|TRACE)]");
    private static final Pattern BARE_LEVEL = Pattern.compile("(?i)\\b(ERROR|WARN|WARNING|INFO|DEBUG|TRACE)\\b");
    private static final Pattern HTTP_STATUS = Pattern.compile("\"\\s+(\\d{3})\\s");

    private static final Pattern APP_TS = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3})");
    private static final Pattern ACCESS_TS = Pattern.compile("\\[(\\d{2}/[A-Za-z]{3}/\\d{4}:\\d{2}:\\d{2}:\\d{2} [+-]\\d{4})]");
    private static final Pattern ISO_TS = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)(Z|[+-]\\d{2}:?\\d{2})?");

    private static final DateTimeFormatter APP_LOG_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");
    private static final DateTimeFormatter ACCESS_LOG_FORMAT = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

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
            message = new StringBuilder(line);
        }
        if (message != null) {
            result.add(new ParsedLine(timestamp, level, message.toString()));
        }
        return result;
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
                // fall through to the default below
            }
        }
        return Instant.now();
    }

    public record ParsedLine(Instant timestamp, LogLevel level, String message) {
    }
}
