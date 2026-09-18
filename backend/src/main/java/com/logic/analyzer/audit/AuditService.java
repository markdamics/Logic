package com.logic.analyzer.audit;

import com.logic.analyzer.audit.dto.AuditLogEntryResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Records one append-only row per admin action (create/update/delete) against
 * LogSource, AlertRule, and RedactionRule (LOGIC-110). Callers pass a
 * {@code Map<String, Object>} snapshot of the entity's own non-secret fields
 * (each entity's owning service builds this, mirroring how each already owns
 * its *Response#from conversion) rather than this class knowing every
 * entity's shape - and rather than reusing the *Response DTOs directly, since
 * those carry java.time.Instant fields this project's ObjectMapper has no
 * module registered for.
 */
@Service
public class AuditService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANONYMOUS_PRINCIPAL = "anonymousUser";

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public List<AuditLogEntryResponse> listRecent() {
        return repository.findTop500ByOrderByTimestampDesc().stream().map(AuditLogEntryResponse::from).toList();
    }

    public void recordCreate(AuditEntityType entityType, Long entityId, String entityName, Map<String, Object> newValue) {
        save(entityType, entityId, entityName, AuditAction.CREATE, null, newValue);
    }

    public void recordUpdate(AuditEntityType entityType, Long entityId, String entityName,
                              Map<String, Object> oldValue, Map<String, Object> newValue) {
        save(entityType, entityId, entityName, AuditAction.UPDATE, oldValue, newValue);
    }

    public void recordDelete(AuditEntityType entityType, Long entityId, String entityName, Map<String, Object> oldValue) {
        save(entityType, entityId, entityName, AuditAction.DELETE, oldValue, null);
    }

    private void save(AuditEntityType entityType, Long entityId, String entityName, AuditAction action,
                       Map<String, Object> oldValue, Map<String, Object> newValue) {
        repository.save(new AuditLogEntry(entityType, entityId, entityName, action, currentActor(),
                oldValue == null ? null : JSON.writeValueAsString(oldValue),
                newValue == null ? null : JSON.writeValueAsString(newValue)));
    }

    /** "anonymous" covers both app.auth.enabled=false (Spring's default AnonymousAuthenticationToken) and no request-bound context at all. */
    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || ANONYMOUS_PRINCIPAL.equals(auth.getName())) {
            return "anonymous";
        }
        return auth.getName();
    }
}
