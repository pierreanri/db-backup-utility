package io.github.pierreanri.dbbackup.notify;

import io.github.pierreanri.dbbackup.config.NotificationsConfig;

/**
 * Builds the notifier configured in {@code notifications}.
 */
public final class Notifiers {

    private Notifiers() {
    }

    public static Notifier fromConfig(NotificationsConfig config) {
        return Notifier.NONE;
    }
}
