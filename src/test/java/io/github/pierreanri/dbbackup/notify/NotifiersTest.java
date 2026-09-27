/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import io.github.pierreanri.dbbackup.config.NotificationsConfig;
import io.github.pierreanri.dbbackup.config.NotificationsConfig.SlackConfig;
import io.github.pierreanri.dbbackup.config.NotificationsConfig.WebhookConfig;
import io.github.pierreanri.dbbackup.logging.ActivityEntry;
import io.github.pierreanri.dbbackup.logging.ActivityEntry.Operation;
import io.github.pierreanri.dbbackup.logging.ActivityEntry.Status;
import io.github.pierreanri.dbbackup.util.Mappers;

class NotifiersTest {

    record Request(String path, String authorization, JsonNode body) {
    }

    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private String baseUrl;
    private int responseStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                requests.add(new Request(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        Mappers.json().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8))));
            }
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static ActivityEntry entry(Status status, String message) {
        return new ActivityEntry(Instant.parse("2026-09-27T02:00:00Z"), Operation.BACKUP, status, "app", "postgresql",
                "app-20260927T020000Z", 2048L, 1500L, List.of("s3://b/app/x"), "schedule:nightly", message, "srv1");
    }

    @Test
    void slackReportsFailuresByDefault() {
        Notifier notifier = Notifiers.fromConfig(new NotificationsConfig(
                new SlackConfig(baseUrl + "/slack", "#ops", "backup-bot", null, null), null));

        notifier.notify(entry(Status.SUCCESS, null));
        notifier.notify(entry(Status.FAILED, "pg_dump failed with exit code 1"));

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/slack");
            assertThat(request.body().get("text").asText()).isEqualTo(":rotating_light: dbbackup backup of 'app' FAILED "
                    + "on srv1 (app-20260927T020000Z, 2.0 KiB, 1.5 s): pg_dump failed with exit code 1");
            assertThat(request.body().get("channel").asText()).isEqualTo("#ops");
            assertThat(request.body().get("username").asText()).isEqualTo("backup-bot");
        });
    }

    @Test
    void webhookSendsTheEntryAsJsonWithHeaders() {
        Notifier notifier = Notifiers.fromConfig(new NotificationsConfig(null,
                new WebhookConfig(baseUrl + "/hook", Map.of("Authorization", "Bearer t0ken"), true, true)));

        notifier.notify(entry(Status.SUCCESS, null));

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.authorization()).isEqualTo("Bearer t0ken");
            assertThat(request.body().get("operation").asText()).isEqualTo("BACKUP");
            assertThat(request.body().get("status").asText()).isEqualTo("SUCCESS");
            assertThat(request.body().get("backupId").asText()).isEqualTo("app-20260927T020000Z");
            assertThat(request.body().get("text").asText()).contains("succeeded");
        });
    }

    @Test
    void bothChannelsCanBeCombinedAndFailuresNeverThrow() {
        responseStatus = 500;
        Notifier notifier = Notifiers.fromConfig(new NotificationsConfig(
                new SlackConfig(baseUrl + "/slack", null, null, true, true),
                new WebhookConfig(baseUrl + "/hook", null, null, null)));

        notifier.notify(entry(Status.PARTIAL, "gcs: timeout"));
        Notifiers.fromConfig(new NotificationsConfig(new SlackConfig("http://127.0.0.1:1/unreachable", null, null,
                true, true), null)).notify(entry(Status.SUCCESS, null));

        assertThat(requests).extracting(Request::path).containsExactlyInAnyOrder("/slack", "/hook");
    }

    @Test
    void nothingConfiguredMeansNoNotifier() {
        assertThat(Notifiers.fromConfig(NotificationsConfig.NONE)).isSameAs(Notifier.NONE);
        assertThat(Notifiers.fromConfig(new NotificationsConfig(new SlackConfig("", null, null, null, null), null)))
                .isSameAs(Notifier.NONE);
    }
}
