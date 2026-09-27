/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import java.util.List;

import io.github.pierreanri.dbbackup.config.DatabaseConfig;

/**
 * Parameters of a dump.
 *
 * @param database connection settings
 * @param scope    full, schema-only or data-only
 * @param tables   tables/collections to include; everything when empty
 */
public record BackupRequest(DatabaseConfig database, BackupScope scope, List<String> tables) {

    public BackupRequest {
        scope = scope == null ? BackupScope.FULL : scope;
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    public static BackupRequest full(DatabaseConfig database) {
        return new BackupRequest(database, BackupScope.FULL, List.of());
    }
}
