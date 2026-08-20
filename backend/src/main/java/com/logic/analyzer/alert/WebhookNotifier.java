package com.logic.analyzer.alert;

import com.logic.analyzer.search.query.QueryLanguage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * Fires a signed webhook when an alert rule triggers - the app's whole
 * "automated remediation / incident-tool integration" story. Logic never
 * runs external commands itself; it POSTs a payload and lets whatever's on
 * the other end (n8n, GitHub Actions dispatch, PagerDuty, a custom runner)
 * decide what to actually do. Fire-and-forget on a small background pool so
 * a slow/dead endpoint never blocks alert evaluation; single attempt, no
 * retry queue (see README Known simplifications).
 */
@Component
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "webhook-notifier");
        thread.setDaemon(true);
        return thread;
    });

    /** Fires asynchronously; {@code onStatus} is called with the HTTP status once the attempt completes (not called at all on total failure). */
    public void notifyAsync(AlertRule rule, Instant triggeredAt, double metricValue, IntConsumer onStatus) {
        String url = rule.getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        executor.submit(() -> {
            try {
                int status = send(rule, triggeredAt, metricValue, url);
                onStatus.accept(status);
            } catch (Exception e) {
                log.warn("Webhook delivery failed for alert rule {} ('{}'): {}", rule.getId(), rule.getName(), e.getMessage());
            }
        });
    }

    private int send(AlertRule rule, Instant triggeredAt, double metricValue, String url) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ruleId", rule.getId());
        payload.put("ruleName", rule.getName());
        payload.put("ruleType", rule.getRuleType().name());
        payload.put("triggeredAt", triggeredAt.toString());
        payload.put("metricValue", metricValue);
        payload.put("threshold", rule.getThreshold());
        payload.put("query", rule.getQueryLanguage() == QueryLanguage.SIMPLE ? rule.getSearch() : rule.getQuery());

        String body = JSON.writeValueAsString(payload);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (rule.getWebhookSecret() != null && !rule.getWebhookSecret().isBlank()) {
            requestBuilder.header("X-Logic-Signature", sign(body, rule.getWebhookSecret()));
        }

        HttpResponse<Void> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding());
        log.info("Webhook fired for alert rule {} ('{}') -> {}", rule.getId(), rule.getName(), response.statusCode());
        return response.statusCode();
    }

    /** {@code sha256=<hex hmac>}, GitHub/Stripe-style, so the receiving endpoint can verify the payload's authenticity. */
    static String sign(String body, String secret) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
        byte[] signature = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + HexFormat.of().formatHex(signature);
    }
}
