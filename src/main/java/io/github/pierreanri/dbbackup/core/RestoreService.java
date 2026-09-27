package io.github.pierreanri.dbbackup.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
import io.github.pierreanri.dbbackup.db.DumpResult;
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
 * Restores a backup: locate it (and, for incremental and differential backups, the backups it
 * depends on), download, verify the checksums, decrypt, decompress and hand over to the database
 * adapter.
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

    /** One backup of the chain being restored. */
    private record Link(BackupManifest manifest, Path file) {
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
            Files.createDirectories(workDir);
            runDir = Files.createTempDirectory(workDir, "restore-");

            List<Link> chain;
            StorageBackend storage = null;
            if (job.localFile() != null) {
                Path file = job.localFile();
                if (!Files.isRegularFile(file)) {
                    throw new DbBackupException("Backup file not found: " + file);
                }
                source = file.toAbsolutePath().toString();
                chain = localChain(file);
                manifest = chain.get(chain.size() - 1).manifest();
            } else {
                storage = storages.get(job.storage());
                manifest = locate(job.storage(), job.backupId(), db.name());
                source = storage.location(manifest.backupKey());
                chain = new ArrayList<>();
                for (BackupManifest link : new BackupCatalog(storage).chain(manifest)) {
                    chain.add(new Link(link, null));
                }
            }
            for (Link link : chain) {
                if (link.manifest() != null) {
                    checkCompatible(link.manifest(), db);
                }
            }
            boolean physical = manifest != null && DumpResult.PHYSICAL.equals(manifest.method());
            if (physical && job.targetDirectory() == null) {
                throw new DbBackupException("Backup " + manifest.id() + " is a physical backup: restore it into a "
                        + "directory with --target-dir");
            }
            if (!physical && job.targetDirectory() != null) {
                throw new DbBackupException("--target-dir is only used for physical backups (PostgreSQL with "
                        + "'incremental: true')");
            }
            if (db.type() != DatabaseType.SQLITE && !physical) {
                LOG.info("Checking connection to {}", db.describe());
                adapter.testConnection(db);
            }
            if (chain.size() > 1) {
                LOG.info("Restoring a chain of {} backups: {}", chain.size(),
                        String.join(" -> ", chain.stream().map(l -> l.manifest().id()).toList()));
            }

            List<Path> raws = new ArrayList<>();
            for (int i = 0; i < chain.size(); i++) {
                raws.add(prepare(chain.get(i), storage, runDir.resolve(String.format("%03d", i)), job));
            }

            String sourceDatabase = manifest != null ? manifest.databaseName() : db.database();
            RestoreRequest request = new RestoreRequest(db, job.targetDatabase(), sourceDatabase, job.tables(),
                    job.clean(), job.targetDirectory());
            adapter.restoreChain(request, raws.get(0), raws.subList(1, raws.size()));

            RestoreResult result = new RestoreResult(manifest, source, clock.millis() - start.toEpochMilli(),
                    chain.stream().filter(l -> l.manifest() != null).map(l -> l.manifest().id()).toList());
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

    /** Downloads (if needed), verifies, decrypts and decompresses one backup; returns the raw dump. */
    private Path prepare(Link link, StorageBackend storage, Path dir, RestoreJob job) throws IOException {
        Files.createDirectories(dir);
        BackupManifest manifest = link.manifest();
        Path file = link.file();
        if (file == null) {
            file = dir.resolve(manifest.fileName());
            LOG.info("Downloading {} ({})", storage.location(manifest.backupKey()),
                    FileUtils.humanSize(manifest.sizeBytes()));
            storage.download(manifest.backupKey(), file);
        }
        String name = file.getFileName().toString();
        if (manifest != null && job.verify()) {
            String actual = Checksums.sha256(file);
            if (!actual.equalsIgnoreCase(manifest.sha256())) {
                throw new DbBackupException("Checksum mismatch for " + manifest.fileName() + ": expected "
                        + manifest.sha256() + ", got " + actual + ". The backup is corrupted.");
            }
            LOG.info("Checksum of {} verified", manifest.id());
        }

        boolean encrypted = manifest != null ? manifest.encrypted() : AgeCrypto.isEncrypted(file);
        if (encrypted) {
            String plainName = name.endsWith(AgeCrypto.EXTENSION)
                    ? name.substring(0, name.length() - AgeCrypto.EXTENSION.length()) : name + ".decrypted";
            Path decrypted = dir.resolve(plainName);
            LOG.info("Decrypting {} (age)", name);
            AgeCrypto.decrypt(file, decrypted, AgeCrypto.identities(encryption, job.identityFiles()),
                    encryption.passphrase());
            file = decrypted;
            name = plainName;
        }

        Compression compression = manifest != null ? manifest.compression() : Compression.fromFileName(name);
        if (compression == Compression.NONE) {
            return file;
        }
        Path raw = dir.resolve(compression.stripExtension(name));
        if (raw.equals(file)) {
            raw = dir.resolve("dump.raw");
        }
        LOG.info("Decompressing {} ({})", name, compression);
        Compressor.decompress(file, raw, compression);
        return raw;
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

    /**
     * A local backup file and, when its manifest sits next to it and it is incremental or
     * differential, the files of the backups it depends on (expected in the same directory).
     */
    private static List<Link> localChain(Path file) {
        Path dir = file.toAbsolutePath().getParent();
        String name = file.getFileName().toString();
        List<BackupManifest> manifests = new ArrayList<>();
        try (DirectoryStream<Path> candidates = Files.newDirectoryStream(dir, "*" + BackupNaming.MANIFEST_SUFFIX)) {
            for (Path candidate : candidates) {
                try {
                    manifests.add(Mappers.json().readValue(candidate.toFile(), BackupManifest.class));
                } catch (IOException e) {
                    LOG.debug("Ignoring unreadable manifest {}", candidate);
                }
            }
        } catch (IOException e) {
            LOG.debug("Cannot look for manifests next to {}: {}", file, e.getMessage());
        }
        BackupManifest own = manifests.stream().filter(m -> name.equals(m.fileName())).findFirst().orElse(null);
        if (own == null) {
            return List.of(new Link(null, file));
        }
        LOG.info("Using manifest {}{}", own.manifestKey().substring(own.database().length() + 1),
                own.isFull() ? "" : " (" + own.backupType() + " backup)");
        List<Link> chain = new ArrayList<>();
        for (BackupManifest link : BackupCatalog.resolveChain(own, manifests, dir.toString())) {
            Path linkFile = dir.resolve(link.fileName());
            if (!Files.isRegularFile(linkFile)) {
                throw new DbBackupException("Backup file " + linkFile + " needed to restore " + own.id() + " is missing");
            }
            chain.add(new Link(link, linkFile));
        }
        return chain;
    }

    private void record(DatabaseConfig db, BackupManifest manifest, String source, Instant start, RestoreJob job,
            Exception error) {
        String target = job.targetDirectory() != null ? job.targetDirectory().toString()
                : job.targetDatabase() != null ? job.targetDatabase()
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
