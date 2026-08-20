package com.logic.analyzer.alert;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookNotifierTest {

    @Test
    void signatureMatchesAnIndependentlyComputedHmac() throws Exception {
        String body = "{\"ruleId\":1,\"metricValue\":7.0}";
        String secret = "s3cr3t-key";

        String actual = WebhookNotifier.sign(body, secret);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void differentBodiesProduceDifferentSignatures() throws Exception {
        String secret = "s3cr3t-key";

        String signatureA = WebhookNotifier.sign("{\"a\":1}", secret);
        String signatureB = WebhookNotifier.sign("{\"a\":2}", secret);

        assertThat(signatureA).isNotEqualTo(signatureB);
    }

    @Test
    void differentSecretsProduceDifferentSignaturesForTheSameBody() throws Exception {
        String body = "{\"a\":1}";

        String signatureA = WebhookNotifier.sign(body, "secret-one");
        String signatureB = WebhookNotifier.sign(body, "secret-two");

        assertThat(signatureA).isNotEqualTo(signatureB);
    }
}
