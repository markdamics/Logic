export type Screen = "dashboard" | "logs" | "sources" | "alerts";

export const SCREEN_TITLES: Record<Screen, string> = {
  dashboard: "Dashboard",
  logs: "Log Stream",
  sources: "Sources",
  alerts: "Alerts",
};
