package com.logic.analyzer.appconfig;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigControllerTest {

    @Test
    void returnsNullWhenTheTemplateIsUnconfigured() {
        AppConfigController controller = new AppConfigController("");

        assertThat(controller.get().apmTraceUrlTemplate()).isNull();
    }

    @Test
    void returnsTheConfiguredTemplate() {
        AppConfigController controller = new AppConfigController("https://app.datadoghq.com/apm/trace/{traceId}");

        assertThat(controller.get().apmTraceUrlTemplate()).isEqualTo("https://app.datadoghq.com/apm/trace/{traceId}");
    }
}
