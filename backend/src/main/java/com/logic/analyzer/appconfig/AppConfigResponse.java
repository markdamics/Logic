package com.logic.analyzer.appconfig;

/** {@code apmTraceUrlTemplate} is null when unconfigured - the frontend renders no "Open in APM" link in that case. */
public record AppConfigResponse(String apmTraceUrlTemplate) {
}
