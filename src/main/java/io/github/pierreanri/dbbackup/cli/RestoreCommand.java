/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.AppConfig;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.core.BackupManifest;
import io.github.pierreanri.dbbackup.core.RestoreJob;
import io.github.pierreanri.dbbackup.core.RestoreResult;
import io.github.pierreanri.dbbackup.core.RestoreService;
import io.github.pierreanri.dbbackup.db.DatabaseType;
import io.github.pierreanri.dbbackup.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "restore", mixinStandardHelpOptions = true, abbreviateSynopsis = true,
        header = "Restore a backup into a database.",
        description = "Downloads the backup, verifies its SHA-256 checksum, decompresses it and restores it. "
                + "Searches every configured storage target unless --storage is given.",
        footer = {"%nExamples:",
            "  dbbackup restore latest --db app",
            "  dbbackup restore app-20260927T020000Z --target-database app_copy",
            "  dbbackup restore latest --db app --tables users --storage s3",
            "  dbbackup restore --file ./app-20260927T020000Z.dump.gz --db app --clean"})
class RestoreCommand extends BaseCommand {

    /** Source of confirmation answers; replaced in tests. */
    static BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    @Parameters(paramLabel = "BACKUP_ID", arity = "0..1",
            description = "Id of the backup (see 'dbbackup list') or 'latest'.")
    String backupId;

    @Option(names = "--db", paramLabel = "DATABASE",
            description = "Database profile to restore into (default: the profile the backup was taken from).")
    String profile;

    @Mixin
    DatabaseOptions dbOptions;

    @Mixin
    StorageOptions storageOptions;

    @Option(names = {"-f", "--file"}, paramLabel = "FILE", description = "Restore this local backup file instead.")
    Path file;

    @Option(names = "--target-database", paramLabel = "NAME",
            description = "Restore into this database (SQLite: file) instead of the profile's.")
    String targetDatabase;

    @Option(names = "--target-dir", paramLabel = "DIR",
            description = "Directory receiving a physical backup (PostgreSQL with 'incremental: true'). "
                    + "It must not exist or be empty.")
    Path targetDir;

    @Option(names = {"-t", "--tables"}, split = ",", paramLabel = "TABLE",
            description = "Only restore these tables/collections (PostgreSQL, MongoDB, SQLite).")
    List<String> tables = new ArrayList<>();

    @Option(names = "--clean", description = "Drop existing objects before restoring them (PostgreSQL, MongoDB).")
    boolean clean;

    @Option(names = {"-i", "--identity"}, paramLabel = "FILE",
            description = "age identity file used to decrypt encrypted backups (in addition to encryption.identityFiles).")
    List<Path> identities = new ArrayList<>();

    @Option(names = "--no-verify", description = "Skip the checksum verification.")
    boolean noVerify;

    @Option(names = {"-y", "--yes"}, description = "Do not ask for confirmation.")
    boolean yes;

    @Override
    public Integer call() throws IOException {
        AppConfig config = ctx().config();
        RestoreService service = ctx().restoreService();
        if (file == null && backupId == null) {
            throw new DbBackupException("Give a BACKUP_ID (or 'latest') or --file");
        }

        String storage = null;
        BackupManifest manifest = null;
        DatabaseConfig target;
        if (file != null) {
            target = resolveTarget(config, null);
        } else {
            String database = profile;
            if ("latest".equalsIgnoreCase(backupId) && database == null) {
                throw new DbBackupException("'latest' needs --db to know which database to restore");
            }
            DbBackupException notFound = null;
            for (String candidate : storageOptions.resolve(ctx(), true)) {
                try {
                    manifest = service.locate(candidate, backupId, database);
                    storage = candidate;
                    break;
                } catch (DbBackupException e) {
                    notFound = e;
                }
            }
            if (manifest == null) {
                throw notFound != null ? notFound : new DbBackupException("Backup '" + backupId + "' not found");
            }
            target = resolveTarget(config, manifest.database());
        }

        if (!confirm(target, manifest, storage)) {
            out().println("Restore cancelled.");
            return FAILED;
        }
        RestoreResult result = service.restore(new RestoreJob(target, storage,
                manifest != null ? manifest.id() : null, file, targetDatabase, targetDir, tables, clean, !noVerify,
                identities, "cli"));
        out().printf("Restored %s into %s in %s%n",
                result.manifest() != null ? result.manifest().id() : result.source(),
                describeTarget(target), FileUtils.humanDuration(result.durationMillis()));
        if (result.chain().size() > 1) {
            out().printf("  applied:   %s%n", String.join(" -> ", result.chain()));
        }
        out().flush();
        return OK;
    }

    private DatabaseConfig resolveTarget(AppConfig config, String backupProfile) {
        if (profile != null) {
            return dbOptions.resolve(config, profile);
        }
        if (dbOptions.isAdHoc()) {
            return dbOptions.resolve(config, null);
        }
        if (backupProfile != null && config.databases().containsKey(backupProfile)) {
            return dbOptions.resolve(config, backupProfile);
        }
        throw new DbBackupException(backupProfile == null
                ? "Tell where to restore: --db PROFILE or --db-type with connection options"
                : "The backup belongs to '" + backupProfile + "', which is not configured: use --db or --db-type");
    }

    private String describeTarget(DatabaseConfig target) {
        if (targetDir != null) {
            return "directory " + targetDir.toAbsolutePath();
        }
        if (targetDatabase != null) {
            return target.name() + " (" + (target.type() == DatabaseType.SQLITE ? "file " : "database ")
                    + targetDatabase + ")";
        }
        return target.name() + " (" + target.describe() + ")";
    }

    private boolean confirm(DatabaseConfig target, BackupManifest manifest, String storage) throws IOException {
        String what = manifest != null
                ? manifest.id() + " (" + manifest.backupType() + ", " + Formats.time(manifest.createdAt()) + ", "
                        + FileUtils.humanSize(manifest.sizeBytes()) + (storage != null ? ", from " + storage : "") + ")"
                : file.toString();
        String scope = tables.isEmpty() ? "" : " (tables: " + String.join(", ", tables) + ")";
        out().printf("Restoring %s%n     into %s%s%n", what, describeTarget(target), scope);
        if (yes) {
            return true;
        }
        out().print("Existing data may be overwritten. Continue? [y/N] ");
        out().flush();
        String answer = input.readLine();
        if (answer == null) {
            throw new DbBackupException("No confirmation received: use --yes to restore non-interactively");
        }
        return List.of("y", "yes").contains(answer.trim().toLowerCase(Locale.ROOT));
    }
}
