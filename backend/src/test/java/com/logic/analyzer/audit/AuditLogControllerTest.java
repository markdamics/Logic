package com.logic.analyzer.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogControllerTest {

    @Mock
    private AuditService service;

    @Test
    void listDelegatesToTheService() {
        when(service.listRecent()).thenReturn(java.util.List.of());

        assertThat(new AuditLogController(service).list()).isEmpty();
    }
}
