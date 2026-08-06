package com.logic.analyzer.source.connectivity;

import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.SourceStatus;
import com.logic.analyzer.source.SourceType;
import com.logic.analyzer.source.dto.ConnectionTestResult;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Component
public class HttpConnectivityChecker implements SourceConnectivityChecker {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public Set<SourceType> supports() {
        return Set.of(SourceType.HTTP);
    }

    @Override
    public ConnectionTestResult check(LogSource source) {
        Instant now = Instant.now();
        URI uri;
        try {
            uri = URI.create(source.getPath());
        } catch (IllegalArgumentException e) {
            return new ConnectionTestResult(SourceStatus.UNREACHABLE, "Invalid URL: " + source.getPath(), now);
        }

        try {
            HttpRequest headRequest = HttpRequest.newBuilder(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(TIMEOUT)
                    .build();
            HttpResponse<Void> response = httpClient.send(headRequest, HttpResponse.BodyHandlers.discarding());

            if (isSuccessful(response.statusCode())) {
                return new ConnectionTestResult(SourceStatus.REACHABLE,
                        "HEAD request succeeded (" + response.statusCode() + ")", now);
            }

            // Some servers don't support HEAD; fall back to a ranged GET.
            HttpRequest rangedGet = HttpRequest.newBuilder(uri)
                    .header("Range", "bytes=0-0")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> getResponse = httpClient.send(rangedGet, HttpResponse.BodyHandlers.discarding());

            if (isSuccessful(getResponse.statusCode())) {
                return new ConnectionTestResult(SourceStatus.REACHABLE,
                        "Ranged GET succeeded (" + getResponse.statusCode() + ")", now);
            }
            return new ConnectionTestResult(SourceStatus.UNREACHABLE,
                    "Server responded with status " + getResponse.statusCode(), now);
        } catch (Exception e) {
            return new ConnectionTestResult(SourceStatus.UNREACHABLE, "HTTP request failed: " + e.getMessage(), now);
        }
    }

    private boolean isSuccessful(int statusCode) {
        return statusCode == 200 || statusCode == 206;
    }
}
