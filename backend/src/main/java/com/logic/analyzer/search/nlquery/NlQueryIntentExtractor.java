package com.logic.analyzer.search.nlquery;

import com.logic.analyzer.logstream.LogLevel;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class NlQueryIntentExtractor {

    private NlQueryIntentExtractor() {
    }

    private record LevelTrigger(Pattern pattern, LogLevel level) {
    }

    private static final List<LevelTrigger> LEVEL_TRIGGERS = List.of(
            new LevelTrigger(Pattern.compile("(?i)\\berrors?\\b"), LogLevel.ERROR),
            new LevelTrigger(Pattern.compile("(?i)\\bwarn(?:ing)?s?\\b"), LogLevel.WARN),
            new LevelTrigger(Pattern.compile("(?i)\\binfo(?:rmation)?\\b"), LogLevel.INFO),
            new LevelTrigger(Pattern.compile("(?i)\\bdebug\\b"), LogLevel.DEBUG)
    );

    private record UnitPattern(Pattern pattern, long minutesPerUnit) {
    }

    /** "last N minute(s)/hour(s)/day(s)" (and common abbreviations), longest units first so "last 2 days" isn't mistaken for minutes. */
    private static final List<UnitPattern> RELATIVE_RANGE_PATTERNS = List.of(
            new UnitPattern(Pattern.compile("(?i)\\blast\\s+(\\d+)\\s*(?:days?|d)\\b"), 1440),
            new UnitPattern(Pattern.compile("(?i)\\blast\\s+(\\d+)\\s*(?:hours?|hrs?|h)\\b"), 60),
            new UnitPattern(Pattern.compile("(?i)\\blast\\s+(\\d+)\\s*(?:minutes?|mins?)\\b"), 1)
    );

    private static final Pattern LAST_HOUR = Pattern.compile("(?i)\\blast\\s+hour\\b");
    private static final Pattern LAST_DAY = Pattern.compile("(?i)\\blast\\s+day\\b");
    private static final Pattern TODAY = Pattern.compile("(?i)\\btoday\\b");
    private static final Pattern SINCE_TIME =
            Pattern.compile("(?i)\\bsince\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b");

    private static final Pattern STOPWORDS = Pattern.compile(
            "(?i)\\b(show|me|please|logs?|entries|events|from|in|on|of|the|a|an|for|with|containing|that|have|has|all|and|find|get)\\b");

    public static NlQueryIntent extract(String prompt, List<String> knownSourceNames) {
        String remaining = prompt == null ? "" : prompt;

        String source = null;
        List<String> byLengthDesc = knownSourceNames.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String name : byLengthDesc) {
            Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(name) + "\\b").matcher(remaining);
            if (m.find()) {
                source = name;
                remaining = m.replaceFirst(" ");
                break;
            }
        }

        LogLevel level = null;
        for (LevelTrigger trigger : LEVEL_TRIGGERS) {
            Matcher m = trigger.pattern().matcher(remaining);
            if (m.find()) {
                level = trigger.level();
                remaining = m.replaceFirst(" ");
                break;
            }
        }

        Long rangeMinutes = null;
        RangeMatch rangeMatch = extractRange(remaining);
        if (rangeMatch != null) {
            rangeMinutes = rangeMatch.minutes();
            remaining = rangeMatch.remainingText();
        }

        remaining = STOPWORDS.matcher(remaining).replaceAll(" ");
        remaining = remaining.replaceAll("\\s+", " ").trim();
        String freeText = remaining.isBlank() ? null : remaining;

        return new NlQueryIntent(level, source, freeText, rangeMinutes);
    }

    private record RangeMatch(long minutes, String remainingText) {
    }

    private static RangeMatch extractRange(String text) {
        Matcher since = SINCE_TIME.matcher(text);
        if (since.find()) {
            long minutes = minutesSince(since.group(1), since.group(2), since.group(3));
            return new RangeMatch(minutes, since.replaceFirst(" "));
        }
        for (UnitPattern unit : RELATIVE_RANGE_PATTERNS) {
            Matcher m = unit.pattern().matcher(text);
            if (m.find()) {
                long amount = Long.parseLong(m.group(1));
                return new RangeMatch(amount * unit.minutesPerUnit(), m.replaceFirst(" "));
            }
        }
        Matcher lastHour = LAST_HOUR.matcher(text);
        if (lastHour.find()) {
            return new RangeMatch(60, lastHour.replaceFirst(" "));
        }
        Matcher lastDay = LAST_DAY.matcher(text);
        if (lastDay.find()) {
            return new RangeMatch(1440, lastDay.replaceFirst(" "));
        }
        Matcher today = TODAY.matcher(text);
        if (today.find()) {
            return new RangeMatch(minutesSinceMidnight(), today.replaceFirst(" "));
        }
        return null;
    }

    private static long minutesSinceMidnight() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime midnight = now.toLocalDate().atStartOfDay(now.getZone());
        return Math.max(1, Duration.between(midnight, now).toMinutes());
    }

    /** "since 2pm"/"since 14:30" - assumes today, or yesterday if that time hasn't happened yet today. */
    private static long minutesSince(String hourStr, String minuteStr, String amPm) {
        int hour = Integer.parseInt(hourStr);
        int minute = minuteStr == null ? 0 : Integer.parseInt(minuteStr);
        int hour24 = hour % 12 + ("pm".equalsIgnoreCase(amPm) ? 12 : 0);

        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime target = now.toLocalDate().atTime(hour24, minute).atZone(now.getZone());
        if (target.isAfter(now)) {
            target = target.minusDays(1);
        }
        return Math.max(1, Duration.between(target, now).toMinutes());
    }
}
