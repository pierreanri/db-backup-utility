/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import java.nio.file.Path;
import java.util.Map;

import io.github.pierreanri.dbbackup.config.DatabaseConfig;

/**
 * Parameters of an incremental or differential backup.
 *
 * @param database       connection settings
 * @param type           incremental or differential
 * @param fromCheckpoint checkpoint of the backup the changes are relative to
 * @param fromState      state file of that backup (downloaded), or {@code null}
 * @param method         method of the chain ({@code logical} or {@code physical})
 */
public record ChangesRequest(
        DatabaseConfig database,
        BackupType type,
        Map<String, String> fromCheckpoint,
        Path fromState,
        String method) {

    public ChangesRequest {
        fromCheckpoint = fromCheckpoint == null ? Map.of() : Map.copyOf(fromCheckpoint);
    }
}
