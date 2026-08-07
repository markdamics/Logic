export type Screen = "dashboard" | "logs" | "sources";

export const SCREEN_TITLES: Record<Screen, string> = {
  dashboard: "Dashboard",
  logs: "Log Stream",
  sources: "Sources",
};
