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

import io.github.pierreanri.dbbackup.config.NotificationsConfig.SlackConfig;
import io.github.pierreanri.dbbackup.logging.ActivityEntry;
import io.github.pierreanri.dbbackup.logging.ActivityEntry.Status;
import io.github.pierreanri.dbbackup.util.Mappers;

/**
 * Posts a message to a Slack incoming webhook. By default only failures are reported.
 */
public class SlackNotifier implements Notifier {

    private static final Logger LOG = LoggerFactory.getLogger(SlackNotifier.class);

    private final SlackConfig config;
    private final HttpSender sender;

    public SlackNotifier(SlackConfig config, HttpSender sender) {
        this.config = config;
        this.sender = sender;
    }

    @Override
    public void notify(ActivityEntry entry) {
        if (!Messages.shouldSend(entry.status(), config.onSuccess(), config.onFailure())) {
            return;
        }
        String icon = entry.status() == Status.SUCCESS ? ":white_check_mark:" : ":rotating_light:";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", icon + " " + Messages.summary(entry));
        if (config.channel() != null && !config.channel().isBlank()) {
            payload.put("channel", config.channel());
        }
        if (config.username() != null && !config.username().isBlank()) {
            payload.put("username", config.username());
        }
        try {
            int status = sender.postJson(URI.create(config.webhookUrl()), Map.of(),
                    Mappers.json().writeValueAsString(payload));
            if (status / 100 != 2) {
                LOG.warn("Slack notification failed with HTTP {}", status);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Slack notification interrupted");
        } catch (Exception e) {
            LOG.warn("Slack notification failed: {}", e.getMessage());
        }
    }
}
