package io.github.pierreanri.dbbackup.notify;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal JSON POST client based on the JDK {@link HttpClient}.
 */
public class HttpSender {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Posts a JSON body and returns the HTTP status code. */
    public int postJson(URI uri, Map<String, String> headers, String json) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "dbbackup")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
