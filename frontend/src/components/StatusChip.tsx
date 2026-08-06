import type { SourceStatus } from "../api/types";

const LABELS: Record<SourceStatus, string> = {
  UNVERIFIED: "Unverified",
  REACHABLE: "Reachable",
  UNREACHABLE: "Unreachable",
};

export function StatusChip({ status }: { status: SourceStatus }) {
  return (
    <span className={`status-chip status-${status.toLowerCase()}`}>
      {LABELS[status]}
    </span>
  );
}
