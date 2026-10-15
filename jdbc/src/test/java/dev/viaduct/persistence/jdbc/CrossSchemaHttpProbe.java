package dev.viaduct.persistence.jdbc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/** Waits for asynchronous PostgREST schema-cache reload after the fixture commits its DDL. */
final class CrossSchemaHttpProbe {
    private static final Pattern QUERY_TYPE = Pattern.compile("\"__typename\"\\s*:\\s*\"Query\"");
    private CrossSchemaHttpProbe() {}

    static void awaitReady(String endpoint) throws IOException, InterruptedException {
        if (endpoint == null || endpoint.isBlank()) return;
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme())
                || !("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))
                || !"/rpc/kan37_graphql".equals(uri.getPath())) {
            throw new IllegalArgumentException("Use only the isolated local KAN-37 HTTP gateway");
        }
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ __typename }\"}"))
                    .build();
            String lastResponse = "No response";
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    lastResponse = response.statusCode() + ": " + response.body();
                    if (response.statusCode() == 200 && QUERY_TYPE.matcher(response.body()).find()) return;
                } catch (IOException failure) {
                    lastResponse = failure.toString();
                }
                Thread.sleep(100);
            }
            throw new IOException("PostgREST fixture did not become ready: " + lastResponse);
        }
    }
}
