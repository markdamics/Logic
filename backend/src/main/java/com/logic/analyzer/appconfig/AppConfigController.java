package com.logic.analyzer.appconfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the slice of server-side app.* config the frontend needs but can't
 * otherwise see (env vars aren't visible client-side) - currently just the
 * optional APM deep-link URL template. Deliberately not a rich settings
 * surface: one read-only value, no entity, no persistence.
 */
@RestController
@RequestMapping("/api/config")
public class AppConfigController {

    private final String apmTraceUrlTemplate;

    public AppConfigController(@Value("${app.apm.trace-url-template:}") String apmTraceUrlTemplate) {
        this.apmTraceUrlTemplate = apmTraceUrlTemplate;
    }

    @GetMapping
    public AppConfigResponse get() {
        return new AppConfigResponse(apmTraceUrlTemplate.isBlank() ? null : apmTraceUrlTemplate);
    }
}
