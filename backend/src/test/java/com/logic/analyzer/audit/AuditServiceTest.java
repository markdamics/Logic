package com.logic.analyzer.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository repository;

    private AuditService service() {
        return new AuditService(repository);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordCreateSavesAnEntryWithNoOldValue() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().recordCreate(AuditEntityType.LOG_SOURCE, 1L, "prod-web1", Map.of("name", "prod-web1"));

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(repository).save(captor.capture());
        AuditLogEntry entry = captor.getValue();
        assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.LOG_SOURCE);
        assertThat(entry.getEntityId()).isEqualTo(1L);
        assertThat(entry.getEntityName()).isEqualTo("prod-web1");
        assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(entry.getOldValue()).isNull();
        assertThat(entry.getNewValue()).contains("prod-web1");
    }

    @Test
    void recordDeleteSavesAnEntryWithNoNewValue() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().recordDelete(AuditEntityType.ALERT_RULE, 2L, "high errors", Map.of("name", "high errors"));

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(repository).save(captor.capture());
        AuditLogEntry entry = captor.getValue();
        assertThat(entry.getAction()).isEqualTo(AuditAction.DELETE);
        assertThat(entry.getNewValue()).isNull();
        assertThat(entry.getOldValue()).contains("high errors");
    }

    @Test
    void recordsTheAuthenticatedUsernameAsTheActor() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("admin", "n/a", AuthorityUtils.NO_AUTHORITIES));

        service().recordCreate(AuditEntityType.REDACTION_RULE, 3L, "emails", Map.of());

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo("admin");
    }

    @Test
    void fallsBackToAnonymousWhenAuthIsDisabled() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser", List.of(() -> "ROLE_ANONYMOUS")));

        service().recordCreate(AuditEntityType.REDACTION_RULE, 3L, "emails", Map.of());

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo("anonymous");
    }

    @Test
    void fallsBackToAnonymousWhenThereIsNoAuthenticationAtAll() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service().recordCreate(AuditEntityType.REDACTION_RULE, 3L, "emails", Map.of());

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo("anonymous");
    }
}
