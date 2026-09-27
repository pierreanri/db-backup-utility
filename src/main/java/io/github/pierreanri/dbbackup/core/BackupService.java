/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.cli.VersionProvider;
import io.github.pierreanri.dbbackup.compression.Checksums;
import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.compression.Compressor;
import io.github.pierreanri.dbbackup.config.AppConfig;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.config.EncryptionConfig;
import io.github.pierreanri.dbbackup.crypto.AgeCrypto;
import io.github.pierreanri.dbbackup.config.RetentionConfig;
import io.github.pierreanri.dbbackup.core.BackupResult.TargetResult;
import io.github.pierreanri.dbbackup.db.BackupRequest;
import io.github.pierreanri.dbbackup.db.BackupScope;
import io.github.pierreanri.dbbackup.db.BackupType;
import io.github.pierreanri.dbbackup.db.ChangesRequest;
import io.github.pierreanri.dbbackup.db.DatabaseAdapter;
import io.github.pierreanri.dbbackup.db.DatabaseAdapters;
import io.github.pierreanri.dbbackup.db.DatabaseType;
import io.github.pierreanri.dbbackup.db.DumpResult;
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
 * Runs a backup: connection test, dump, compression, checksum, upload of the file and its
 * manifest to every storage target, retention, activity log and notifications.
 */
public class BackupService {

    private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);

    private final AppConfig config;
    private final DatabaseAdapters adapters;
    private final StorageRegistry storages;
    private final ActivityLog activityLog;
    private final Notifier notifier;
    private final Path workDir;
    private final Clock clock;

    public BackupService(AppConfig config, DatabaseAdapters adapters, StorageRegistry storages, ActivityLog activityLog,
            Notifier notifier, Path workDir, Clock clock) {
        this.config = config;
        this.adapters = adapters;
        this.storages = storages;
        this.activityLog = activityLog;
        this.notifier = notifier;
        this.workDir = workDir;
        this.clock = clock;
    }

    public BackupResult backup(BackupJob job) {
        if (job.storage().isEmpty()) {
            throw new DbBackupException("No storage target given");
        }
        DatabaseConfig db = withDefaults(job.database());
        Instant start = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Path runDir = null;
        BackupManifest manifest = null;
        try {
            List<StorageBackend> targets = new ArrayList<>();
            for (String name : job.storage()) {
                targets.add(storages.get(name));
            }
            DatabaseAdapter adapter = adapters.forType(db.type());

            BackupType type = job.type();
            BackupManifest base = null;
            BackupManifest parent = null;
            if (type != BackupType.FULL) {
                checkIncrementalAllowed(db, adapter, job);
                List<BackupManifest> existing = new BackupCatalog(targets.get(0)).list(db.name());
                base = existing.stream().filter(BackupManifest::canStartChain).findFirst().orElse(null);
                if (base == null) {
                    LOG.warn("No full backup of '{}' to build on in '{}': taking a full backup instead", db.name(),
                            targets.get(0).name());
                    type = BackupType.FULL;
                } else if (type == BackupType.DIFFERENTIAL) {
                    parent = base;
                } else {
                    String chain = base.id();
                    parent = existing.stream().filter(m -> chain.equals(m.chainId())).findFirst().orElse(base);
                }
            }

            LOG.info("Backing up '{}' ({}){}", db.name(), db.describe(),
                    parent == null ? "" : ": " + type + " backup based on " + parent.id());
            String serverVersion = adapter.testConnection(db);
            LOG.info("Connected: {}", serverVersion);

            String id = uniqueId(db.name(), start, targets.get(0));
            Files.createDirectories(workDir);
            runDir = Files.createTempDirectory(workDir, "backup-");
            String extension = adapter.fileExtension(db, type);
            Path raw = runDir.resolve("raw-" + id + "." + extension);
            DumpResult dump;
            if (type == BackupType.FULL) {
                dump = adapter.backup(new BackupRequest(db, job.scope(), job.tables()), raw);
            } else {
                Path parentState = null;
                if (parent.stateKey() != null) {
                    parentState = runDir.resolve("parent.state");
                    downloadState(targets.get(0), parent, parentState);
                }
                dump = adapter.backupChanges(new ChangesRequest(db, type, parent.checkpoint(), parentState,
                        parent.method()), raw);
            }
            if (!Files.isRegularFile(raw)) {
                throw new DbBackupException("The dump tool did not produce " + raw.getFileName());
            }
            long rawSize = Files.size(raw);

            EncryptionConfig encryption = config.encryption();
            String fileName = BackupNaming.fileName(id, extension, job.compression(), encryption.enabled());
            Path stored = runDir.resolve(fileName);
            Path compressed = encryption.enabled() ? runDir.resolve(fileName + ".tmp") : stored;
            String sha256 = null;
            if (job.compression() == Compression.NONE) {
                Files.move(raw, compressed);
            } else {
                LOG.info("Compressing {} dump ({})", FileUtils.humanSize(rawSize), job.compression());
                sha256 = Compressor.compress(raw, compressed, job.compression());
                Files.delete(raw);
            }
            if (encryption.enabled()) {
                LOG.info("Encrypting with age");
                AgeCrypto.encrypt(compressed, stored, encryption);
                Files.delete(compressed);
                sha256 = null;
            }
            if (sha256 == null) {
                sha256 = Checksums.sha256(stored);
            }
            long size = Files.size(stored);

            Path stateFile = null;
            String stateName = null;
            if (dump.stateFile() != null) {
                stateName = id + BackupNaming.STATE_SUFFIX;
                stateFile = runDir.resolve(stateName);
                Compressor.compress(dump.stateFile(), stateFile, Compression.GZIP);
            }

            manifest = new BackupManifest(BackupManifest.FORMAT_VERSION, id, db.name(), db.type(),
                    db.type() == DatabaseType.SQLITE ? db.file() : db.database(),
                    db.type().isNetworked() && db.uri() == null ? db.effectiveHost() : null,
                    type, parent == null ? null : parent.id(), base == null ? null : base.id(), dump.method(),
                    dump.checkpoint(), stateName, job.scope(), job.tables(), job.compression(),
                    encryption.enabled() ? AgeCrypto.ALGORITHM : null, fileName, size, rawSize, sha256, start,
                    clock.millis() - start.toEpochMilli(), serverVersion, VersionProvider.version(),
                    HostInfo.hostname());
            byte[] manifestJson = Mappers.json().writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);

            List<TargetResult> results = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                results.add(store(targets.get(i), stored, stateFile, manifest, manifestJson, job,
                        i == 0 ? null : parent));
            }
            BackupResult result = new BackupResult(manifest, results, clock.millis() - start.toEpochMilli());
            record(job, db, manifest, result, start, null);
            return result;
        } catch (IOException e) {
            DbBackupException error = new DbBackupException("Backup of '" + db.name() + "' failed: " + e.getMessage(), e);
            record(job, db, manifest, null, start, error);
            throw error;
        } catch (RuntimeException e) {
            record(job, db, manifest, null, start, e);
            throw e;
        } finally {
            FileUtils.deleteRecursively(runDir);
        }
    }

    private static void checkIncrementalAllowed(DatabaseConfig db, DatabaseAdapter adapter, BackupJob job) {
        if (!adapter.supportsIncremental()) {
            throw new DbBackupException(db.type() + " databases do not support incremental backups");
        }
        if (!db.isIncremental()) {
            throw new DbBackupException("Incremental backups are not enabled for '" + db.name()
                    + "': set 'incremental: true' on the database (see the README for the server requirements)");
        }
        if (job.scope() != BackupScope.FULL || !job.tables().isEmpty()) {
            throw new DbBackupException("Incremental and differential backups cover the whole database: they cannot "
                    + "be combined with --tables, --schema-only or --data-only");
        }
    }

    private static void downloadState(StorageBackend storage, BackupManifest parent, Path target) {
        Path compressed = target.resolveSibling(target.getFileName() + ".gz");
        storage.download(parent.stateKey(), compressed);
        Compressor.decompress(compressed, target, Compression.GZIP);
    }

    private TargetResult store(StorageBackend target, Path file, Path stateFile, BackupManifest manifest,
            byte[] manifestJson, BackupJob job, BackupManifest requiredParent) {
        String location = target.location(manifest.backupKey());
        try {
            if (requiredParent != null && !target.exists(requiredParent.manifestKey())) {
                throw new DbBackupException("the backup " + requiredParent.id() + " this " + manifest.backupType()
                        + " backup depends on is missing here: take a full backup");
            }
            LOG.info("Uploading {} ({}) to {}", manifest.fileName(), FileUtils.humanSize(manifest.sizeBytes()),
                    location);
            // The manifest is written last: its presence marks the backup as complete.
            target.upload(file, manifest.backupKey());
            if (stateFile != null) {
                target.upload(stateFile, manifest.stateKey());
            }
            target.write(manifest.manifestKey(), manifestJson);
        } catch (RuntimeException e) {
            LOG.error("Storing the backup in '{}' failed: {}", target.name(), e.getMessage());
            return new TargetResult(target.name(), location, false, e.getMessage(), List.of());
        }
        List<String> pruned = List.of();
        if (job.applyRetention()) {
            try {
                pruned = applyRetention(target, manifest.database(), retentionFor(target.name(), job.retention()),
                        false);
            } catch (RuntimeException e) {
                LOG.warn("Applying retention on '{}' failed: {}", target.name(), e.getMessage());
            }
        }
        return new TargetResult(target.name(), location, true, null, pruned);
    }

    /**
     * Deletes the expired backups of {@code database} in {@code target}.
     *
     * @return ids of the deleted (or, for a dry run, expired) backups
     */
    public List<String> applyRetention(StorageBackend target, String database, RetentionConfig retention,
            boolean dryRun) {
        RetentionPolicy policy = RetentionPolicy.of(retention);
        if (policy.isEmpty()) {
            return List.of();
        }
        BackupCatalog catalog = new BackupCatalog(target);
        List<BackupManifest> expired = policy.selectExpired(catalog.list(database), clock.instant());
        List<String> ids = new ArrayList<>();
        for (BackupManifest backup : expired) {
            if (dryRun) {
                LOG.info("Would delete expired backup {} from '{}'", backup.id(), target.name());
            } else {
                LOG.info("Deleting expired backup {} from '{}'", backup.id(), target.name());
                catalog.delete(backup);
            }
            ids.add(backup.id());
        }
        return ids;
    }

    /** Retention for a target: override, then the storage's own retention, then the defaults. */
    public RetentionConfig retentionFor(String storageName, RetentionConfig override) {
        RetentionConfig storageRetention = config.storage().containsKey(storageName)
                ? config.storage().get(storageName).retention() : null;
        return RetentionConfig.firstNonEmpty(override, storageRetention, config.defaults().retention());
    }

    private DatabaseConfig withDefaults(DatabaseConfig db) {
        if (db.timeoutMinutes() == null && config.defaults().timeoutMinutes() != null) {
            return db.withTimeoutMinutes(config.defaults().timeoutMinutes());
        }
        return db;
    }

    private static String uniqueId(String database, Instant start, StorageBackend firstTarget) {
        String base = BackupNaming.newId(database, start);
        String id = base;
        for (int n = 2; firstTarget.exists(BackupNaming.manifestKey(database, id)); n++) {
            id = base + "-" + n;
        }
        return id;
    }

    private void record(BackupJob job, DatabaseConfig db, BackupManifest manifest, BackupResult result, Instant start,
            Exception error) {
        Status status;
        String message;
        List<String> locations = new ArrayList<>();
        if (error != null) {
            status = Status.FAILED;
            message = error.getMessage();
        } else {
            status = result.success() ? Status.SUCCESS : result.partial() ? Status.PARTIAL : Status.FAILED;
            List<String> errors = new ArrayList<>();
            for (TargetResult target : result.targets()) {
                if (target.success()) {
                    locations.add(target.location());
                } else {
                    errors.add(target.storage() + ": " + target.error());
                }
            }
            message = errors.isEmpty() ? null : String.join("; ", errors);
        }
        if (message == null && manifest != null && !manifest.isFull()) {
            message = manifest.backupType() + " based on " + manifest.parentId();
        }
        ActivityEntry entry = new ActivityEntry(start, Operation.BACKUP, status, db.name(), db.type().id(),
                manifest == null ? null : manifest.id(), manifest == null ? null : manifest.sizeBytes(),
                clock.millis() - start.toEpochMilli(), locations, job.trigger(), message, HostInfo.hostname());
        activityLog.append(entry);
        notifier.notify(entry);
    }
}
