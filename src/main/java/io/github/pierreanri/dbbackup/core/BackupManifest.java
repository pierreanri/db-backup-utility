package io.github.pierreanri.dbbackup.core;

import java.time.Instant;
import java.util.List;

import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.db.BackupScope;
import io.github.pierreanri.dbbackup.db.DatabaseType;

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
 * @param scope           full, schema-only or data-only
 * @param tables          tables/collections included; empty for all
 * @param compression     compression of the stored file
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
        BackupScope scope,
        List<String> tables,
        Compression compression,
        String fileName,
        long sizeBytes,
        long rawSizeBytes,
        String sha256,
        Instant createdAt,
        long durationMillis,
        String serverVersion,
        String toolVersion,
        String hostname) {

    public static final int FORMAT_VERSION = 1;

    public BackupManifest {
        tables = tables == null ? List.of() : List.copyOf(tables);
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
