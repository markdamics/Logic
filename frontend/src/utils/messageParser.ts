// Best-effort, dependency-free structured-format detection for a single log
// message. Tries each known shape from most to least specific and falls back
// to "unstructured" (raw text only) when nothing matches.

export type MessageFormat = "json" | "syslog" | "accesslog" | "logfmt" | "unstructured";

export interface ParsedField {
  key: string;
  value: string;
}

export interface ParsedMessage {
  format: MessageFormat;
  label: string;
  fields: ParsedField[];
}

const FORMAT_LABELS: Record<MessageFormat, string> = {
  json: "JSON",
  syslog: "Syslog",
  accesslog: "Access log",
  logfmt: "Key-value",
  unstructured: "Unstructured",
};

export function parseMessage(message: string): ParsedMessage {
  if (!message || !message.trim()) {
    return { format: "unstructured", label: FORMAT_LABELS.unstructured, fields: [] };
  }
  return (
    tryJson(message) ??
    trySyslog5424(message) ??
    trySyslog3164(message) ??
    tryAccessLog(message) ??
    trySyslogTag(message) ??
    tryLogfmt(message) ?? { format: "unstructured", label: FORMAT_LABELS.unstructured, fields: [] }
  );
}

function tryJson(message: string): ParsedMessage | null {
  const trimmed = message.trim();
  if (trimmed[0] !== "{" && trimmed[0] !== "[") return null;
  let value: unknown;
  try {
    value = JSON.parse(trimmed);
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null) return null;
  const fields = flattenJson(value);
  if (fields.length === 0) return null;
  return { format: "json", label: FORMAT_LABELS.json, fields };
}

function flattenJson(value: unknown, prefix = "", out: ParsedField[] = []): ParsedField[] {
  if (Array.isArray(value)) {
    value.forEach((item, i) => flattenJson(item, prefix ? `${prefix}[${i}]` : `[${i}]`, out));
  } else if (value !== null && typeof value === "object") {
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      flattenJson(v, prefix ? `${prefix}.${k}` : k, out);
    }
  } else {
    out.push({ key: prefix || "value", value: String(value) });
  }
  return out;
}

// RFC 5424: <PRI>VERSION TIMESTAMP HOSTNAME APP-NAME PROCID MSGID MSG
const SYSLOG_5424 = /^<(\d{1,3})>(\d)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+([\s\S]*)$/;

function trySyslog5424(message: string): ParsedMessage | null {
  const match = SYSLOG_5424.exec(message.trim());
  if (!match) return null;
  const [, pri, version, timestamp, hostname, appName, procId, msgId, msg] = match;
  return {
    format: "syslog",
    label: FORMAT_LABELS.syslog,
    fields: [
      { key: "priority", value: pri },
      { key: "version", value: version },
      { key: "timestamp", value: timestamp },
      { key: "hostname", value: hostname },
      { key: "appName", value: appName },
      { key: "procId", value: procId },
      { key: "msgId", value: msgId },
      { key: "message", value: msg },
    ],
  };
}

// RFC 3164 (BSD syslog): <PRI>Mon dd HH:mm:ss HOSTNAME TAG[PID]: MSG
const SYSLOG_3164 = /^<(\d{1,3})>(\w{3}\s+\d{1,2}\s+\d{2}:\d{2}:\d{2})\s+(\S+)\s+([^:\s[]+)(?:\[(\d+)\])?:\s?([\s\S]*)$/;

function trySyslog3164(message: string): ParsedMessage | null {
  const match = SYSLOG_3164.exec(message.trim());
  if (!match) return null;
  const [, pri, timestamp, hostname, tag, pid, msg] = match;
  const fields: ParsedField[] = [
    { key: "priority", value: pri },
    { key: "timestamp", value: timestamp },
    { key: "hostname", value: hostname },
    { key: "tag", value: tag },
  ];
  if (pid) fields.push({ key: "pid", value: pid });
  fields.push({ key: "message", value: msg });
  return { format: "syslog", label: FORMAT_LABELS.syslog, fields };
}

// Apache/Nginx combined access log:
// host ident authuser [timestamp] "request" status bytes "referer" "user-agent"
// The [timestamp] segment is optional since upstream ingestion may already
// have extracted it into the entry's own timestamp field, leaving it out of
// the message text.
const ACCESS_LOG =
  /^(\S+) (\S+) (\S+) (?:\[([^\]]+)] )?"([^"]*)" (\d{3}) (\S+)(?: "([^"]*)" "([^"]*)")?\s*$/;

function tryAccessLog(message: string): ParsedMessage | null {
  const match = ACCESS_LOG.exec(message.trim());
  if (!match) return null;
  const [, host, ident, authuser, timestamp, request, status, bytes, referer, userAgent] = match;
  const fields: ParsedField[] = [
    { key: "host", value: host },
    { key: "ident", value: ident },
    { key: "authuser", value: authuser },
  ];
  if (timestamp !== undefined) fields.push({ key: "timestamp", value: timestamp });
  fields.push(
    { key: "request", value: request },
    { key: "status", value: status },
    { key: "bytes", value: bytes },
  );
  if (referer !== undefined) fields.push({ key: "referer", value: referer });
  if (userAgent !== undefined) fields.push({ key: "userAgent", value: userAgent });
  return { format: "accesslog", label: FORMAT_LABELS.accesslog, fields };
}

// BSD syslog with the <PRI> and "Mon dd HH:mm:ss" timestamp already stripped
// by ingestion (which extracts them into the entry's own timestamp field),
// leaving: HOSTNAME TAG[PID]: MSG
const SYSLOG_TAG = /^(\S+) ([^:\s[]+)(?:\[(\d+)])?:\s?([\s\S]+)$/;

function trySyslogTag(message: string): ParsedMessage | null {
  const match = SYSLOG_TAG.exec(message.trim());
  if (!match) return null;
  const [, hostname, tag, pid, msg] = match;
  const fields: ParsedField[] = [
    { key: "hostname", value: hostname },
    { key: "tag", value: tag },
  ];
  if (pid) fields.push({ key: "pid", value: pid });
  fields.push({ key: "message", value: msg });
  return { format: "syslog", label: FORMAT_LABELS.syslog, fields };
}

// logfmt / key=value pairs, e.g. `level=error msg="connection refused" retries=3`
const LOGFMT_PAIR = /([A-Za-z_][\w.-]*)=("(?:[^"\\]|\\.)*"|\S+)/g;

function tryLogfmt(message: string): ParsedMessage | null {
  const trimmed = message.trim();
  const fields: ParsedField[] = [];
  let matchedLength = 0;
  LOGFMT_PAIR.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = LOGFMT_PAIR.exec(trimmed)) !== null) {
    const [full, key, rawValue] = match;
    matchedLength += full.length;
    const value = rawValue.startsWith('"') && rawValue.endsWith('"') ? rawValue.slice(1, -1) : rawValue;
    fields.push({ key, value });
  }
  // Require at least two pairs, covering most of the line, to avoid matching
  // stray "a=b" tokens inside ordinary prose.
  if (fields.length < 2 || matchedLength < trimmed.length * 0.6) return null;
  return { format: "logfmt", label: FORMAT_LABELS.logfmt, fields };
}
