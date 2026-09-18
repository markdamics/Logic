package com.logic.analyzer.audit;

import com.logic.analyzer.audit.dto.AuditLogEntryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only by design (LOGIC-110 AC: "audit log itself isn't editable via the API") - no POST/PUT/DELETE mapping exists here at all. */
@RestController
@RequestMapping("/api/audit")
public class AuditLogController {

    private final AuditService service;

    public AuditLogController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public List<AuditLogEntryResponse> list() {
        return service.listRecent();
    }
}
