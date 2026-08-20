import { useEffect, useState } from "react";
import { getAppConfig } from "../api/client";
import type { AppConfig } from "../api/types";
import { createLogger } from "../utils/logger";

const logger = createLogger("useAppConfig");

/** Fetched once; null until it loads. Currently just the optional APM deep-link URL template. */
export function useAppConfig() {
  const [config, setConfig] = useState<AppConfig | null>(null);

  useEffect(() => {
    getAppConfig()
      .then(setConfig)
      .catch((e) => logger.warn("Failed to load app config", e));
  }, []);

  return config;
}
