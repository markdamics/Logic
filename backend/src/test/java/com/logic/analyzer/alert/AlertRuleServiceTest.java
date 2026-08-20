package com.logic.analyzer.alert;

import com.logic.analyzer.alert.dto.AlertRuleCreateRequest;
import com.logic.analyzer.exception.AlertRuleNotFoundException;
import com.logic.analyzer.logstream.LogLevel;
import com.logic.analyzer.search.query.QueryLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertRuleServiceTest {

    @Mock
    private AlertRuleRepository repository;
    @Mock
    private AlertEventRepository eventRepository;
    @Mock
    private WebhookNotifier webhookNotifier;

    private AlertRuleService service() {
        return new AlertRuleService(repository, eventRepository, webhookNotifier);
    }

    private AlertRuleCreateRequest thresholdRequest() {
        return new AlertRuleCreateRequest("high errors", QueryLanguage.SIMPLE, null, null, Set.of(LogLevel.ERROR),
                null, null, AlertRuleType.THRESHOLD, 5, AlertMetric.COUNT, ComparisonOperator.GT, 5.0,
                null, null, null, null);
    }

    private AlertRuleCreateRequest anomalyRequest() {
        return new AlertRuleCreateRequest("spike watch", QueryLanguage.SIMPLE, null, null, Set.of(),
                null, null, AlertRuleType.ANOMALY, 10, AlertMetric.COUNT, null, null, 6, 3.0, null, null);
    }

    @Test
    void rejectsAQueryBarRuleMissingTheRawQuery() {
        AlertRuleCreateRequest request = new AlertRuleCreateRequest("bad", QueryLanguage.SPL, null, null, null,
                null, null, AlertRuleType.THRESHOLD, 5, AlertMetric.COUNT, ComparisonOperator.GT, 1.0,
                null, null, null, null);

        assertThatThrownBy(() -> service().create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void rejectsAThresholdRuleMissingComparisonOrThreshold() {
        AlertRuleCreateRequest request = new AlertRuleCreateRequest("bad", QueryLanguage.SIMPLE, null, null, null,
                null, null, AlertRuleType.THRESHOLD, 5, AlertMetric.COUNT, null, null, null, null, null, null);

        assertThatThrownBy(() -> service().create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
    }

    @Test
    void rejectsAnAnomalyRuleMissingBaselineParams() {
        AlertRuleCreateRequest request = new AlertRuleCreateRequest("bad", QueryLanguage.SIMPLE, null, null, null,
                null, null, AlertRuleType.ANOMALY, 10, AlertMetric.COUNT, null, null, null, null, null, null);

        assertThatThrownBy(() -> service().create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anomaly");
    }

    @Test
    void acceptsAValidThresholdRule() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service().create(thresholdRequest());

        assertThat(response.name()).isEqualTo("high errors");
        assertThat(response.ruleType()).isEqualTo(AlertRuleType.THRESHOLD);
        assertThat(response.levels()).containsExactly(LogLevel.ERROR);
        assertThat(response.webhookSecretConfigured()).isFalse();
    }

    @Test
    void acceptsAValidAnomalyRule() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service().create(anomalyRequest());

        assertThat(response.ruleType()).isEqualTo(AlertRuleType.ANOMALY);
        assertThat(response.anomalyBaselineWindows()).isEqualTo(6);
    }

    @Test
    void webhookSecretConfiguredIsTrueWhenASecretIsSet() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AlertRuleCreateRequest request = new AlertRuleCreateRequest("hooked", QueryLanguage.SIMPLE, null, null, null,
                null, null, AlertRuleType.THRESHOLD, 5, AlertMetric.COUNT, ComparisonOperator.GT, 1.0,
                null, null, "https://example.invalid/hook", "s3cr3t");

        var response = service().create(request);

        assertThat(response.webhookSecretConfigured()).isTrue();
        assertThat(response.webhookUrl()).isEqualTo("https://example.invalid/hook");
    }

    @Test
    void deleteThrowsWhenTheRuleDoesNotExist() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service().delete(99L))
                .isInstanceOf(AlertRuleNotFoundException.class);
    }

    @Test
    void muteAndUnmuteTogglePersist() {
        AlertRule rule = new AlertRule("r", QueryLanguage.SIMPLE, null, null, null, null, null,
                AlertRuleType.THRESHOLD, 5, AlertMetric.COUNT, ComparisonOperator.GT, 1.0, null, null, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(rule));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var muted = service().setMuted(1L, true);
        assertThat(muted.muted()).isTrue();
        assertThat(rule.isMuted()).isTrue();

        var unmuted = service().setMuted(1L, false);
        assertThat(unmuted.muted()).isFalse();
    }

    @Test
    void testWebhookThrowsWhenNoUrlIsConfigured() {
        AlertRule rule = new AlertRule("r", QueryLanguage.SIMPLE, null, null, null, null, null,
                AlertRuleType.THRESHOLD, 5, AlertMetric.COUNT, ComparisonOperator.GT, 1.0, null, null, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(rule));

        assertThatThrownBy(() -> service().testWebhook(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("webhook");
    }
}
