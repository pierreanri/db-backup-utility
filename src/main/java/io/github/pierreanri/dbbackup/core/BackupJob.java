package io.github.pierreanri.dbbackup.core;

import java.util.List;

import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.config.RetentionConfig;
import io.github.pierreanri.dbbackup.db.BackupScope;

/**
 * What to back up and where.
 *
 * @param database       database profile
 * @param storage        names of the storage targets
 * @param compression    compression of the stored file
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
        trigger = trigger == null ? "cli" : trigger;
    }
}
