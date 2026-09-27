/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.logging;

import java.time.Instant;
import java.util.List;

/**
 * One record of the activity history ({@code history.jsonl}).
 *
 * @param timestamp      when the operation started
 * @param operation      backup, restore or prune
 * @param status         outcome
 * @param database       database profile name
 * @param databaseType   database type
 * @param backupId       id of the backup created or restored
 * @param sizeBytes      size of the stored backup file
 * @param durationMillis duration of the operation
 * @param locations      where the backup was stored / read from
 * @param trigger        {@code cli} or {@code schedule:<name>}
 * @param message        summary or error message
 * @param host           machine the operation ran on
 */
public record ActivityEntry(
        Instant timestamp,
        Operation operation,
        Status status,
        String database,
        String databaseType,
        String backupId,
        Long sizeBytes,
        Long durationMillis,
        List<String> locations,
        String trigger,
        String message,
        String host) {

    public ActivityEntry {
        locations = locations == null ? List.of() : List.copyOf(locations);
    }

    public enum Operation {
        BACKUP, RESTORE, PRUNE
    }

    public enum Status {
        SUCCESS, PARTIAL, FAILED
    }
}
