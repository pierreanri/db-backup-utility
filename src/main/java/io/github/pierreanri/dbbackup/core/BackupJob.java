/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.core;

import java.util.List;

import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.config.RetentionConfig;
import io.github.pierreanri.dbbackup.db.BackupScope;
import io.github.pierreanri.dbbackup.db.BackupType;

/**
 * What to back up and where.
 *
 * @param database       database profile
 * @param storage        names of the storage targets
 * @param compression    compression of the stored file
 * @param type           full, incremental or differential
 * @param scope          full, schema-only or data-only
 * @param tables         tables/collections to include; all when empty
 * @param retention      retention override (command line or schedule); may be {@code null}
 * @param applyRetention whether expired backups are deleted after the upload
 * @param trigger        {@code cli} or {@code schedule:<name>}
 */
public record BackupJob(
        DatabaseConfig database,
        List<String> storage,
        Compression compression,
        BackupType type,
        BackupScope scope,
        List<String> tables,
        RetentionConfig retention,
        boolean applyRetention,
        String trigger) {

    public BackupJob {
        storage = List.copyOf(storage);
        tables = tables == null ? List.of() : List.copyOf(tables);
        compression = compression == null ? Compression.GZIP : compression;
        scope = scope == null ? BackupScope.FULL : scope;
        type = type == null ? BackupType.FULL : type;
        trigger = trigger == null ? "cli" : trigger;
    }
}
