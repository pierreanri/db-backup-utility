/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.core;

import java.util.List;

/**
 * Outcome of a restore.
 *
 * @param manifest       manifest of the restored backup, {@code null} for a file without manifest
 * @param source         location the backup was read from
 * @param durationMillis total duration
 * @param chain          ids of the backups applied, full backup first
 */
public record RestoreResult(BackupManifest manifest, String source, long durationMillis, List<String> chain) {

    public RestoreResult {
        chain = chain == null ? List.of() : List.copyOf(chain);
    }
}
