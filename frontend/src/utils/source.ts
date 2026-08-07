import type { LogSource } from "../api/types";

/** Describes a source's pending-update state, naming the exact file(s) that changed. */
export function updateMessageFor(source: LogSource): string {
  const files = source.changedFiles.join(", ");
  const verb = source.changedFiles.length > 1 ? "have" : "has";
  return `The ${files} ${verb} been modified - ${source.name}`;
}
