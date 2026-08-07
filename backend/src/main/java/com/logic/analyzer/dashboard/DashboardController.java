package com.logic.analyzer.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    public DashboardSummary summary() {
        log.info("GET /api/dashboard/summary");
        DashboardSummary summary = dashboardService.summarize();
        log.debug("Dashboard summary: {} sources, {} entries in last 24h ({} errors, {} warnings)",
                summary.totalSources(), summary.entriesLast24h(), summary.errorsLast24h(), summary.warningsLast24h());
        return summary;
    }
}
