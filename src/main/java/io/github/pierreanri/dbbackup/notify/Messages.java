/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.notify;

import java.util.Locale;

import io.github.pierreanri.dbbackup.logging.ActivityEntry;
import io.github.pierreanri.dbbackup.logging.ActivityEntry.Status;
import io.github.pierreanri.dbbackup.util.FileUtils;

/**
 * Human readable one-line summaries of activity entries.
 */
final class Messages {

    private Messages() {
    }

    static String summary(ActivityEntry entry) {
        String operation = entry.operation().name().toLowerCase(Locale.ROOT);
        String outcome = switch (entry.status()) {
            case SUCCESS -> "succeeded";
            case PARTIAL -> "partially failed";
            case FAILED -> "FAILED";
        };
        StringBuilder text = new StringBuilder();
        text.append("dbbackup ").append(operation).append(" of '").append(entry.database()).append("' ")
                .append(outcome).append(" on ").append(entry.host());
        StringBuilder details = new StringBuilder();
        if (entry.backupId() != null) {
            details.append(entry.backupId());
        }
        if (entry.sizeBytes() != null) {
            append(details, FileUtils.humanSize(entry.sizeBytes()));
        }
        if (entry.durationMillis() != null) {
            append(details, FileUtils.humanDuration(entry.durationMillis()));
        }
        if (!details.isEmpty()) {
            text.append(" (").append(details).append(')');
        }
        if (entry.status() != Status.SUCCESS && entry.message() != null) {
            text.append(": ").append(entry.message());
        }
        return text.toString();
    }

    static boolean shouldSend(Status status, Boolean onSuccess, Boolean onFailure) {
        return status == Status.SUCCESS ? Boolean.TRUE.equals(onSuccess) : onFailure == null || onFailure;
    }

    private static void append(StringBuilder builder, String value) {
        if (!builder.isEmpty()) {
            builder.append(", ");
        }
        builder.append(value);
    }
}
