package io.github.pierreanri.dbbackup.notify;

import java.util.ArrayList;
import java.util.List;

import io.github.pierreanri.dbbackup.config.NotificationsConfig;

/**
 * Builds the notifier configured in {@code notifications}.
 */
public final class Notifiers {

    private Notifiers() {
    }

    public static Notifier fromConfig(NotificationsConfig config) {
        return fromConfig(config, new HttpSender());
    }

    static Notifier fromConfig(NotificationsConfig config, HttpSender sender) {
        List<Notifier> notifiers = new ArrayList<>();
        if (config != null && config.slack() != null && notBlank(config.slack().webhookUrl())) {
            notifiers.add(new SlackNotifier(config.slack(), sender));
        }
        if (config != null && config.webhook() != null && notBlank(config.webhook().url())) {
            notifiers.add(new WebhookNotifier(config.webhook(), sender));
        }
        if (notifiers.isEmpty()) {
            return Notifier.NONE;
        }
        return entry -> notifiers.forEach(notifier -> notifier.notify(entry));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
