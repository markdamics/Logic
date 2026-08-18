package com.logic.analyzer.search.extract;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side port of frontend/src/utils/messageParser.ts - same detector
 * priority, same regexes - so indexed log messages get individually
 * filterable structured fields (field.&lt;name&gt;) for JSON/syslog/access-log
 * /logfmt shaped content, not just full-text search over the raw message.
 * Best-effort/heuristic, same as the frontend original: not a full grok or
 * schema engine, and won't recognize every possible format.
 */
public final class MessageFieldExtractor {

    public record Field(String name, String value) {
    }

    public record ExtractedFields(String format, List<Field> fields) {
        private static final ExtractedFields UNSTRUCTURED = new ExtractedFields("unstructured", List.of());
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    // RFC 5424: <PRI>VERSION TIMESTAMP HOSTNAME APP-NAME PROCID MSGID MSG
    private static final Pattern SYSLOG_5424 =
            Pattern.compile("^<(\\d{1,3})>(\\d)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+([\\s\\S]*)$");

    // RFC 3164 (BSD syslog): <PRI>Mon dd HH:mm:ss HOSTNAME TAG[PID]: MSG
    private static final Pattern SYSLOG_3164 =
            Pattern.compile("^<(\\d{1,3})>(\\w{3}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+(\\S+)\\s+([^:\\s\\[]+)(?:\\[(\\d+)])?:\\s?([\\s\\S]*)$");

    // Apache/Nginx combined access log; the [timestamp] segment is optional
    // since upstream ingestion may already have extracted it separately.
    private static final Pattern ACCESS_LOG =
            Pattern.compile("^(\\S+) (\\S+) (\\S+) (?:\\[([^]]+)] )?\"([^\"]*)\" (\\d{3}) (\\S+)(?: \"([^\"]*)\" \"([^\"]*)\")?\\s*$");

    // BSD syslog with <PRI> and the timestamp already stripped by ingestion: HOSTNAME TAG[PID]: MSG
    private static final Pattern SYSLOG_TAG =
            Pattern.compile("^(\\S+) ([^:\\s\\[]+)(?:\\[(\\d+)])?:\\s?([\\s\\S]+)$");

    // logfmt / key=value pairs, e.g. `level=error msg="connection refused" retries=3`
    private static final Pattern LOGFMT_PAIR =
            Pattern.compile("([A-Za-z_][\\w.-]*)=(\"(?:[^\"\\\\]|\\\\.)*\"|\\S+)");

    private MessageFieldExtractor() {
    }

    public static ExtractedFields extract(String message) {
        if (message == null || message.isBlank()) {
            return ExtractedFields.UNSTRUCTURED;
        }
        ExtractedFields result;
        if ((result = tryJson(message)) != null) return result;
        if ((result = trySyslog5424(message)) != null) return result;
        if ((result = trySyslog3164(message)) != null) return result;
        if ((result = tryAccessLog(message)) != null) return result;
        if ((result = trySyslogTag(message)) != null) return result;
        if ((result = tryLogfmt(message)) != null) return result;
        return ExtractedFields.UNSTRUCTURED;
    }

    private static ExtractedFields tryJson(String message) {
        String trimmed = message.trim();
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[')) {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(trimmed);
        } catch (Exception e) {
            return null;
        }
        if (!root.isObject() && !root.isArray()) {
            return null;
        }
        List<Field> fields = new ArrayList<>();
        flattenJson(root, "", fields);
        if (fields.isEmpty()) {
            return null;
        }
        return new ExtractedFields("json", fields);
    }

    private static void flattenJson(JsonNode node, String prefix, List<Field> out) {
        if (node.isArray()) {
            int i = 0;
            for (JsonNode item : node) {
                flattenJson(item, prefix.isEmpty() ? "[" + i + "]" : prefix + "[" + i + "]", out);
                i++;
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                flattenJson(e.getValue(), prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), out);
            }
        } else {
            out.add(new Field(prefix.isEmpty() ? "value" : prefix, node.asText()));
        }
    }

    private static ExtractedFields trySyslog5424(String message) {
        Matcher m = SYSLOG_5424.matcher(message.trim());
        if (!m.matches()) {
            return null;
        }
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("priority", m.group(1)));
        fields.add(new Field("version", m.group(2)));
        fields.add(new Field("timestamp", m.group(3)));
        fields.add(new Field("hostname", m.group(4)));
        fields.add(new Field("appName", m.group(5)));
        fields.add(new Field("procId", m.group(6)));
        fields.add(new Field("msgId", m.group(7)));
        fields.add(new Field("message", m.group(8)));
        return new ExtractedFields("syslog", fields);
    }

    private static ExtractedFields trySyslog3164(String message) {
        Matcher m = SYSLOG_3164.matcher(message.trim());
        if (!m.matches()) {
            return null;
        }
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("priority", m.group(1)));
        fields.add(new Field("timestamp", m.group(2)));
        fields.add(new Field("hostname", m.group(3)));
        fields.add(new Field("tag", m.group(4)));
        if (m.group(5) != null) {
            fields.add(new Field("pid", m.group(5)));
        }
        fields.add(new Field("message", m.group(6)));
        return new ExtractedFields("syslog", fields);
    }

    private static ExtractedFields tryAccessLog(String message) {
        Matcher m = ACCESS_LOG.matcher(message.trim());
        if (!m.matches()) {
            return null;
        }
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("host", m.group(1)));
        fields.add(new Field("ident", m.group(2)));
        fields.add(new Field("authuser", m.group(3)));
        if (m.group(4) != null) {
            fields.add(new Field("timestamp", m.group(4)));
        }
        fields.add(new Field("request", m.group(5)));
        fields.add(new Field("status", m.group(6)));
        fields.add(new Field("bytes", m.group(7)));
        if (m.group(8) != null) {
            fields.add(new Field("referer", m.group(8)));
        }
        if (m.group(9) != null) {
            fields.add(new Field("userAgent", m.group(9)));
        }
        return new ExtractedFields("accesslog", fields);
    }

    private static ExtractedFields trySyslogTag(String message) {
        Matcher m = SYSLOG_TAG.matcher(message.trim());
        if (!m.matches()) {
            return null;
        }
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("hostname", m.group(1)));
        fields.add(new Field("tag", m.group(2)));
        if (m.group(3) != null) {
            fields.add(new Field("pid", m.group(3)));
        }
        fields.add(new Field("message", m.group(4)));
        return new ExtractedFields("syslog", fields);
    }

    private static ExtractedFields tryLogfmt(String message) {
        String trimmed = message.trim();
        List<Field> fields = new ArrayList<>();
        int matchedLength = 0;
        Matcher m = LOGFMT_PAIR.matcher(trimmed);
        while (m.find()) {
            matchedLength += m.group(0).length();
            String rawValue = m.group(2);
            String value = rawValue.startsWith("\"") && rawValue.endsWith("\"") && rawValue.length() >= 2
                    ? rawValue.substring(1, rawValue.length() - 1)
                    : rawValue;
            fields.add(new Field(m.group(1), value));
        }
        // Require at least two pairs, covering most of the line, to avoid matching
        // stray "a=b" tokens inside ordinary prose.
        if (fields.size() < 2 || matchedLength < trimmed.length() * 0.6) {
            return null;
        }
        return new ExtractedFields("logfmt", fields);
    }
}
