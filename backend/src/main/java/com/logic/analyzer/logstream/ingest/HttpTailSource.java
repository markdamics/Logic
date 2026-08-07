package com.logic.analyzer.logstream.ingest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

public class HttpTailSource implements TailSource {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final URI uri;

    public HttpTailSource(URI uri) {
        this.uri = uri;
    }

    @Override
    public TailBytes readTail(int maxBytes) throws IOException {
        // A suffix range ("last N bytes") avoids a separate HEAD/size lookup and is
        // widely supported by static file servers per RFC 7233.
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Range", "bytes=-" + maxBytes)
                .timeout(TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 206) {
                boolean fromStart = response.headers().firstValue("Content-Range")
                        .map(HttpTailSource::startsAtZero)
                        .orElse(false);
                return new TailBytes(response.body(), fromStart);
            }
            if (status == 200) {
                // Server ignored the Range header; bound how much we keep in memory.
                // Best-effort: if the file is larger than maxBytes this yields the
                // START of the file rather than the tail, since we can't seek
                // without range support - a known limitation for such servers.
                byte[] body = response.body();
                byte[] bounded = body.length <= maxBytes ? body : Arrays.copyOf(body, maxBytes);
                return new TailBytes(bounded, true);
            }
            throw new IOException("Unexpected HTTP status " + status + " for " + uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading " + uri, e);
        }
    }

    private static boolean startsAtZero(String contentRange) {
        // Expected shape: "bytes 0-499/1234"
        try {
            String range = contentRange.substring(contentRange.indexOf(' ') + 1, contentRange.indexOf('-'));
            return Long.parseLong(range.trim()) == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Fingerprint probe() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(TIMEOUT)
                .build();
        try {
            HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 400) {
                throw new IOException("HEAD " + uri + " returned " + response.statusCode());
            }
            long size = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            Instant lastModified = response.headers().firstValue("Last-Modified")
                    .map(HttpTailSource::parseHttpDate)
                    .orElse(null);
            return new Fingerprint(size, lastModified);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while probing " + uri, e);
        }
    }

    private static Instant parseHttpDate(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
