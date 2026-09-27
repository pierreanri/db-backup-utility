package io.github.pierreanri.dbbackup.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.compression.Checksums;
import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.compression.Compressor;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.config.EncryptionConfig;
import io.github.pierreanri.dbbackup.crypto.AgeCrypto;
import io.github.pierreanri.dbbackup.db.DatabaseAdapter;
import io.github.pierreanri.dbbackup.db.DatabaseAdapters;
import io.github.pierreanri.dbbackup.db.DatabaseType;
import io.github.pierreanri.dbbackup.db.RestoreRequest;
import io.github.pierreanri.dbbackup.logging.ActivityEntry;
import io.github.pierreanri.dbbackup.logging.ActivityEntry.Operation;
import io.github.pierreanri.dbbackup.logging.ActivityEntry.Status;
import io.github.pierreanri.dbbackup.logging.ActivityLog;
import io.github.pierreanri.dbbackup.notify.Notifier;
import io.github.pierreanri.dbbackup.storage.StorageBackend;
import io.github.pierreanri.dbbackup.util.FileUtils;
import io.github.pierreanri.dbbackup.util.HostInfo;
import io.github.pierreanri.dbbackup.util.Mappers;

/**
 * Restores a backup: locate, download, verify the checksum, decompress and hand over to the
 * database adapter.
 */
public class RestoreService {

    private static final Logger LOG = LoggerFactory.getLogger(RestoreService.class);

    private final DatabaseAdapters adapters;
    private final StorageRegistry storages;
    private final EncryptionConfig encryption;
    private final ActivityLog activityLog;
    private final Notifier notifier;
    private final Path workDir;
    private final Clock clock;

    public RestoreService(DatabaseAdapters adapters, StorageRegistry storages, EncryptionConfig encryption,
            ActivityLog activityLog, Notifier notifier, Path workDir, Clock clock) {
        this.adapters = adapters;
        this.storages = storages;
        this.encryption = encryption == null ? EncryptionConfig.NONE : encryption;
        this.activityLog = activityLog;
        this.notifier = notifier;
        this.workDir = workDir;
        this.clock = clock;
    }

    /** Resolves the manifest of a stored backup without restoring it. */
    public BackupManifest locate(String storageName, String backupId, String database) {
        StorageBackend storage = storages.get(storageName);
        return new BackupCatalog(storage).find(backupId, database).orElseThrow(() -> new DbBackupException(
                "Backup '" + backupId + "' not found in storage '" + storageName + "'"
                        + (database != null ? " for database '" + database + "'" : "")));
    }

    public RestoreResult restore(RestoreJob job) {
        DatabaseConfig db = job.target();
        Instant start = clock.instant();
        Path runDir = null;
        BackupManifest manifest = null;
        String source = null;
        try {
            DatabaseAdapter adapter = adapters.forType(db.type());
            if (!job.tables().isEmpty() && !adapter.supportsSelectiveRestore()) {
                throw new DbBackupException(db.type() + " backups cannot be restored table by table");
            }
            if (db.type() != DatabaseType.SQLITE) {
                LOG.info("Checking connection to {}", db.describe());
                adapter.testConnection(db);
            }

            Files.createDirectories(workDir);
            runDir = Files.createTempDirectory(workDir, "restore-");
            Path file;
            Compression compression;
            if (job.localFile() != null) {
                file = job.localFile();
                if (!Files.isRegularFile(file)) {
                    throw new DbBackupException("Backup file not found: " + file);
                }
                source = file.toAbsolutePath().toString();
                manifest = siblingManifest(file);
                String name = file.getFileName().toString();
                if (name.endsWith(AgeCrypto.EXTENSION)) {
                    name = name.substring(0, name.length() - AgeCrypto.EXTENSION.length());
                }
                compression = manifest != null ? manifest.compression() : Compression.fromFileName(name);
            } else {
                StorageBackend storage = storages.get(job.storage());
                manifest = locate(job.storage(), job.backupId(), db.name());
                source = storage.location(manifest.backupKey());
                file = runDir.resolve(manifest.fileName());
                LOG.info("Downloading {} ({})", source, FileUtils.humanSize(manifest.sizeBytes()));
                storage.download(manifest.backupKey(), file);
                compression = manifest.compression();
            }

            if (manifest != null) {
                checkCompatible(manifest, db);
                if (job.verify()) {
                    String actual = Checksums.sha256(file);
                    if (!actual.equalsIgnoreCase(manifest.sha256())) {
                        throw new DbBackupException("Checksum mismatch for " + manifest.fileName() + ": expected "
                                + manifest.sha256() + ", got " + actual + ". The backup is corrupted.");
                    }
                    LOG.info("Checksum verified");
                }
            }

            boolean encrypted = manifest != null ? manifest.encrypted() : AgeCrypto.isEncrypted(file);
            if (encrypted) {
                String name = file.getFileName().toString();
                Path decrypted = runDir.resolve(name.endsWith(AgeCrypto.EXTENSION)
                        ? name.substring(0, name.length() - AgeCrypto.EXTENSION.length()) : name + ".decrypted");
                LOG.info("Decrypting (age)");
                AgeCrypto.decrypt(file, decrypted, AgeCrypto.identities(encryption, job.identityFiles()),
                        encryption.passphrase());
                file = decrypted;
            }

            Path raw = file;
            if (compression != Compression.NONE) {
                raw = runDir.resolve(compression.stripExtension(file.getFileName().toString()));
                if (raw.equals(file)) {
                    raw = runDir.resolve("dump.raw");
                }
                LOG.info("Decompressing ({})", compression);
                Compressor.decompress(file, raw, compression);
            }

            String sourceDatabase = manifest != null ? manifest.databaseName() : db.database();
            adapter.restore(new RestoreRequest(db, job.targetDatabase(), sourceDatabase, job.tables(), job.clean()), raw);

            RestoreResult result = new RestoreResult(manifest, source, clock.millis() - start.toEpochMilli());
            record(db, manifest, source, start, job, null);
            return result;
        } catch (IOException e) {
            DbBackupException error = new DbBackupException("Restore failed: " + e.getMessage(), e);
            record(db, manifest, source, start, job, error);
            throw error;
        } catch (RuntimeException e) {
            record(db, manifest, source, start, job, e);
            throw e;
        } finally {
            FileUtils.deleteRecursively(runDir);
        }
    }

    private static void checkCompatible(BackupManifest manifest, DatabaseConfig db) {
        DatabaseType from = manifest.databaseType();
        DatabaseType to = db.type();
        boolean mysqlFamily = (from == DatabaseType.MYSQL || from == DatabaseType.MARIADB)
                && (to == DatabaseType.MYSQL || to == DatabaseType.MARIADB);
        if (from != to && !mysqlFamily) {
            throw new DbBackupException("Backup " + manifest.id() + " is a " + from + " backup and cannot be restored "
                    + "into the " + to + " database '" + db.name() + "'");
        }
    }

    /** Finds the manifest describing a local backup file, if it sits next to it. */
    private static BackupManifest siblingManifest(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        String name = file.getFileName().toString();
        try (DirectoryStream<Path> manifests = Files.newDirectoryStream(dir, "*" + BackupNaming.MANIFEST_SUFFIX)) {
            for (Path candidate : manifests) {
                try {
                    BackupManifest manifest = Mappers.json().readValue(candidate.toFile(), BackupManifest.class);
                    if (name.equals(manifest.fileName())) {
                        LOG.info("Using manifest {}", candidate.getFileName());
                        return manifest;
                    }
                } catch (IOException e) {
                    LOG.debug("Ignoring unreadable manifest {}", candidate);
                }
            }
        } catch (IOException e) {
            LOG.debug("Cannot look for a manifest next to {}: {}", file, e.getMessage());
        }
        return null;
    }

    private void record(DatabaseConfig db, BackupManifest manifest, String source, Instant start, RestoreJob job,
            Exception error) {
        String target = job.targetDatabase() != null ? job.targetDatabase()
                : db.type() == DatabaseType.SQLITE ? db.file() : db.database();
        String message = error != null ? error.getMessage() : "restored into " + target;
        ActivityEntry entry = new ActivityEntry(start, Operation.RESTORE, error == null ? Status.SUCCESS : Status.FAILED,
                db.name(), db.type().id(), manifest == null ? null : manifest.id(),
                manifest == null ? null : manifest.sizeBytes(), clock.millis() - start.toEpochMilli(),
                source == null ? List.of() : List.of(source), job.trigger(), message, HostInfo.hostname());
        activityLog.append(entry);
        notifier.notify(entry);
    }
}
