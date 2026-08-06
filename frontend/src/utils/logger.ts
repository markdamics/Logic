type Level = "debug" | "info" | "warn" | "error";

function emit(level: Level, scope: string, message: string, ...args: unknown[]) {
  const prefix = `[${scope}]`;
  // eslint-disable-next-line no-console
  console[level](prefix, message, ...args);
}

/** Small console-wrapping logger so app events have a consistent, filterable shape in devtools. */
export function createLogger(scope: string) {
  return {
    debug: (message: string, ...args: unknown[]) => emit("debug", scope, message, ...args),
    info: (message: string, ...args: unknown[]) => emit("info", scope, message, ...args),
    warn: (message: string, ...args: unknown[]) => emit("warn", scope, message, ...args),
    error: (message: string, ...args: unknown[]) => emit("error", scope, message, ...args),
  };
}
