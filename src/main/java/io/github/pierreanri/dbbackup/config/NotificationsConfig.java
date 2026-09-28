package io.github.pierreanri.dbbackup.config;

import java.util.Map;

/**
 * Notification channels triggered after backups and restores.
 *
 * @param slack   Slack incoming webhook
 * @param webhook generic HTTP webhook receiving a JSON document
 */
public record NotificationsConfig(SlackConfig slack, WebhookConfig webhook) {

    public static final NotificationsConfig NONE = new NotificationsConfig(null, null);

    /**
     * @param webhookUrl incoming webhook URL
     * @param channel    optional channel override
     * @param username   optional bot name
     * @param onSuccess  notify successful operations (default false)
     * @param onFailure  notify failed operations (default true)
     */
    public record SlackConfig(String webhookUrl, String channel, String username, Boolean onSuccess, Boolean onFailure) {
    }

    /**
     * @param url       URL receiving a POST with a JSON body
     * @param headers   extra HTTP headers, e.g. an Authorization header
     * @param onSuccess notify successful operations (default false)
     * @param onFailure notify failed operations (default true)
     */
    public record WebhookConfig(String url, Map<String, String> headers, Boolean onSuccess, Boolean onFailure) {

        public WebhookConfig {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }
}
