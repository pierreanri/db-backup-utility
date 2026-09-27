package io.github.pierreanri.dbbackup.notify;

import io.github.pierreanri.dbbackup.logging.ActivityEntry;

/**
 * Receives the outcome of backup and restore operations. Implementations must never throw.
 */
@FunctionalInterface
public interface Notifier {

    Notifier NONE = entry -> {
    };

    void notify(ActivityEntry entry);
}
