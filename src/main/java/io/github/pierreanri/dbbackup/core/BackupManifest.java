package io.github.pierreanri.dbbackup.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.db.BackupScope;
import io.github.pierreanri.dbbackup.db.BackupType;
import io.github.pierreanri.dbbackup.db.DatabaseType;
import io.github.pierreanri.dbbackup.db.DumpResult;

/**
 * Metadata stored next to every backup file ({@code <id>.manifest.json}). A backup is only
 * considered complete once its manifest exists.
 *
 * @param formatVersion   manifest format version
 * @param id              backup id, {@code <database>-<yyyyMMdd'T'HHmmss'Z'>}
 * @param database        database profile name
 * @param databaseType    database type
 * @param databaseName    name of the backed up database (SQLite: file path); {@code null} when all
 *                        databases of a MongoDB server were dumped
 * @param host            server host, {@code null} for SQLite
 * @param backupType      full, incremental or differential
 * @param parentId        backup this one contains the changes since (incremental/differential)
 * @param baseId          full backup at the root of the chain (incremental/differential)
 * @param method          {@code logical} dump or {@code physical} copy
 * @param checkpoint      engine specific position the next incremental backup starts from
 * @param stateFile       name of an additional (unencrypted) file needed by the next incremental
 * @param scope           full, schema-only or data-only
 * @param tables          tables/collections included; empty for all
 * @param compression     compression of the stored file
 * @param encryption      {@code age} when the stored file is encrypted, {@code null} otherwise
 * @param fileName        name of the stored backup file
 * @param sizeBytes       size of the stored (compressed) file
 * @param rawSizeBytes    size of the uncompressed dump
 * @param sha256          SHA-256 of the stored file
 * @param createdAt       start of the backup
 * @param durationMillis  time taken to dump and compress
 * @param serverVersion   version reported by the database server
 * @param toolVersion     version of dbbackup that created the backup
 * @param hostname        machine that created the backup
 */
public record BackupManifest(
        int formatVersion,
        String id,
        String database,
        DatabaseType databaseType,
        String databaseName,
        String host,
        BackupType backupType,
        String parentId,
        String baseId,
        String method,
        Map<String, String> checkpoint,
        String stateFile,
        BackupScope scope,
        List<String> tables,
        Compression compression,
        String encryption,
        String fileName,
        long sizeBytes,
        long rawSizeBytes,
        String sha256,
        Instant createdAt,
        long durationMillis,
        String serverVersion,
        String toolVersion,
        String hostname) {

    public static final int FORMAT_VERSION = 2;

    public BackupManifest {
        tables = tables == null ? List.of() : List.copyOf(tables);
        backupType = backupType == null ? BackupType.FULL : backupType;
        method = method == null ? DumpResult.LOGICAL : method;
        checkpoint = checkpoint == null ? Map.of() : Map.copyOf(checkpoint);
    }

    public boolean isFull() {
        return backupType == BackupType.FULL;
    }

    /** Id of the chain this backup belongs to: its own id for a full backup, its base otherwise. */
    public String chainId() {
        return isFull() ? id : baseId;
    }

    /** Whether incremental backups can be based on this backup. */
    public boolean canStartChain() {
        return isFull() && !checkpoint.isEmpty();
    }

    /** Storage key of the state file, or {@code null}. */
    public String stateKey() {
        return stateFile == null ? null : database + "/" + stateFile;
    }

    public boolean encrypted() {
        return encryption != null;
    }

    /** Storage key of the backup file. */
    public String backupKey() {
        return database + "/" + fileName;
    }

    /** Storage key of this manifest. */
    public String manifestKey() {
        return BackupNaming.manifestKey(database, id);
    }
}
