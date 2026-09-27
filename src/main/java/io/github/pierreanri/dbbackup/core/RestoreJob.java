package io.github.pierreanri.dbbackup.core;

import java.nio.file.Path;
import java.util.List;

import io.github.pierreanri.dbbackup.config.DatabaseConfig;

/**
 * What to restore and where.
 *
 * @param target         database profile to restore into
 * @param storage        storage target holding the backup (ignored with {@code localFile})
 * @param backupId       backup id or {@code latest} (ignored with {@code localFile})
 * @param localFile      backup file on the local disk, instead of a stored backup
 * @param targetDatabase database (SQLite: file) to restore into; the profile's when {@code null}
 * @param targetDirectory directory receiving a physical backup
 * @param tables         tables/collections to restore; all when empty
 * @param clean          drop existing objects before restoring
 * @param verify         verify the SHA-256 checksum before restoring
 * @param identityFiles  extra age identity files used to decrypt encrypted backups
 * @param trigger        {@code cli} or another origin
 */
public record RestoreJob(
        DatabaseConfig target,
        String storage,
        String backupId,
        Path localFile,
        String targetDatabase,
        Path targetDirectory,
        List<String> tables,
        boolean clean,
        boolean verify,
        List<Path> identityFiles,
        String trigger) {

    public RestoreJob {
        tables = tables == null ? List.of() : List.copyOf(tables);
        identityFiles = identityFiles == null ? List.of() : List.copyOf(identityFiles);
        trigger = trigger == null ? "cli" : trigger;
    }

    /** Restore without a target directory (logical backups). */
    public RestoreJob(DatabaseConfig target, String storage, String backupId, Path localFile, String targetDatabase,
            List<String> tables, boolean clean, boolean verify, List<Path> identityFiles, String trigger) {
        this(target, storage, backupId, localFile, targetDatabase, null, tables, clean, verify, identityFiles, trigger);
    }
}
