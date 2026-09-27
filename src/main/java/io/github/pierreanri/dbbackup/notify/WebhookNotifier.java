/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.notify;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.config.NotificationsConfig.WebhookConfig;
import io.github.pierreanri.dbbackup.logging.ActivityEntry;
import io.github.pierreanri.dbbackup.util.Mappers;

/**
 * Posts the activity entry as JSON (plus a {@code text} summary) to any HTTP endpoint, e.g. a
 * chat tool, an incident manager or a monitoring system.
 */
public class WebhookNotifier implements Notifier {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookNotifier.class);

    private final WebhookConfig config;
    private final HttpSender sender;

    public WebhookNotifier(WebhookConfig config, HttpSender sender) {
        this.config = config;
        this.sender = sender;
    }

    @Override
    public void notify(ActivityEntry entry) {
        if (!Messages.shouldSend(entry.status(), config.onSuccess(), config.onFailure())) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = new LinkedHashMap<>(Mappers.json().convertValue(entry, Map.class));
            payload.put("text", Messages.summary(entry));
            int status = sender.postJson(URI.create(config.url()), config.headers(),
                    Mappers.json().writeValueAsString(payload));
            if (status / 100 != 2) {
                LOG.warn("Webhook notification failed with HTTP {}", status);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Webhook notification interrupted");
        } catch (Exception e) {
            LOG.warn("Webhook notification failed: {}", e.getMessage());
        }
    }
}
