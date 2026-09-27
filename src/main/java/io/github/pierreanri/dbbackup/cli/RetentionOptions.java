/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.cli;

import io.github.pierreanri.dbbackup.config.RetentionConfig;
import picocli.CommandLine.Option;

/**
 * Retention overrides.
 */
class RetentionOptions {

    @Option(names = "--keep-last", paramLabel = "N", description = "Keep only the N most recent backups.")
    Integer keepLast;

    @Option(names = "--max-age-days", paramLabel = "DAYS", description = "Delete backups older than DAYS days.")
    Integer maxAgeDays;

    RetentionConfig toConfig() {
        if (keepLast != null && keepLast < 1 || maxAgeDays != null && maxAgeDays < 1) {
            throw new io.github.pierreanri.dbbackup.DbBackupException("--keep-last and --max-age-days must be at least 1");
        }
        return keepLast == null && maxAgeDays == null ? null : new RetentionConfig(keepLast, maxAgeDays);
    }
}
