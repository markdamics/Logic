import { AlertsIcon } from "./icons";

export function Alerts() {
  return (
    <div className="alerts-placeholder">
      <div className="alerts-placeholder-panel">
        <AlertsIcon size={28} />
        <h4>Alert rules aren't available yet</h4>
        <p className="text-muted">
          Alerting — threshold rules, mute/arm state, and trigger history — is reserved for a future phase. Search,
          Saved Searches, and the Log Stream/Dashboard are what's live today.
        </p>
      </div>
    </div>
  );
}
