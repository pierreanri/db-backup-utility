package io.github.pierreanri.dbbackup.db;

import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.util.FileUtils;
import io.github.pierreanri.dbbackup.util.TarArchives;

/**
 * PostgreSQL support based on {@code pg_dump} (custom format), {@code pg_restore} and {@code psql}.
 * The password is passed through the {@code PGPASSWORD} environment variable.
 *
 * <p>With {@code incremental: true}, backups are physical instead: {@code pg_basebackup} copies of
 * the whole server, incremental ones relative to the {@code backup_manifest} of their parent
 * (PostgreSQL 17+ with {@code summarize_wal = on}), restored into a data directory with
 * {@code pg_combinebackup}.
 */
public class PostgresAdapter implements DatabaseAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresAdapter.class);
    private static final String HINT = "Install the PostgreSQL client tools (pg_dump, pg_restore, psql) "
            + "or set 'binPath' for this database.";
    private static final byte[] CUSTOM_FORMAT_MAGIC = "PGDMP".getBytes(StandardCharsets.US_ASCII);

    private final ProcessRunner runner;

    public PostgresAdapter(ProcessRunner runner) {
        this.runner = runner;
    }

    @Override
    public String testConnection(DatabaseConfig db) {
        List<String> command = new ArrayList<>(List.of(Executables.resolve(db.binPath(), "psql")));
        command.addAll(connectionArgs(db, db.database()));
        command.addAll(List.of("-X", "-t", "-A", "-c", "SELECT version()"));
        ProcessResult result = runner.run(spec(command, db).timeout(Duration.ofMinutes(2)).build());
        String version = result.stdout().strip();
        int on = version.indexOf(" on ");
        return on > 0 ? version.substring(0, on) : version;
    }

    @Override
    public DumpResult backup(BackupRequest request, Path outputFile) {
        DatabaseConfig db = request.database();
        if (db.isIncremental()) {
            if (request.scope() != BackupScope.FULL || !request.tables().isEmpty()) {
                throw new DbBackupException("With 'incremental: true', PostgreSQL backups are physical copies of the "
                        + "whole server: --tables, --schema-only and --data-only are not available");
            }
            return baseBackup(db, null, outputFile);
        }
        List<String> command = new ArrayList<>(List.of(Executables.resolve(db.binPath(), "pg_dump")));
        command.addAll(connectionArgs(db, db.database()));
        command.addAll(List.of("--format=custom", "--file=" + outputFile.toAbsolutePath()));
        switch (request.scope()) {
            case FULL -> {
            }
            case SCHEMA_ONLY -> command.add("--schema-only");
            case DATA_ONLY -> command.add("--data-only");
        }
        for (String table : request.tables()) {
            command.add("--table=" + table);
        }
        command.addAll(db.dumpArgs());

        LOG.info("Dumping PostgreSQL database '{}' with pg_dump", db.database());
        runner.run(spec(command, db).timeoutMinutes(db.timeoutMinutes()).build());
        return DumpResult.NONE;
    }

    @Override
    public void restore(RestoreRequest request, Path dumpFile) {
        DatabaseConfig db = request.database();
        String target = request.effectiveTargetDatabase();
        if (target == null || target.isBlank()) {
            throw new DbBackupException("No target database: set 'database' on the profile or use --target-database");
        }
        ensureDatabaseExists(db, target);

        List<String> command = new ArrayList<>();
        if (isCustomFormat(dumpFile)) {
            command.add(Executables.resolve(db.binPath(), "pg_restore"));
            command.addAll(connectionArgs(db, target));
            if (request.clean()) {
                command.addAll(List.of("--clean", "--if-exists"));
            }
            for (String table : request.tables()) {
                command.add("--table=" + table);
            }
            command.addAll(db.restoreArgs());
            command.add(dumpFile.toAbsolutePath().toString());
        } else {
            if (!request.tables().isEmpty()) {
                throw new DbBackupException("Plain SQL dumps cannot be restored table by table");
            }
            command.add(Executables.resolve(db.binPath(), "psql"));
            command.addAll(connectionArgs(db, target));
            command.addAll(List.of("-X", "-q", "-v", "ON_ERROR_STOP=1", "-f", dumpFile.toAbsolutePath().toString()));
            command.addAll(db.restoreArgs());
        }
        LOG.info("Restoring PostgreSQL database '{}' from {}", target, dumpFile.getFileName());
        ProcessSpec restore = spec(command, db).timeoutMinutes(db.timeoutMinutes()).build();
        ProcessResult result = runner.execute(restore);
        if (result.exitCode() != 0) {
            if (!onlyHarmlessErrors(result.stderr())) {
                throw ProcessRunner.failure(restore, result);
            }
            LOG.warn("The PostgreSQL client tools are newer than the server: pg_restore could not set "
                    + "transaction_timeout, which the server does not know; everything else was restored. Set "
                    + "'binPath' to tools matching the server version to avoid this warning.");
        }
    }

    /**
     * pg_restore 17 sets {@code transaction_timeout}, unknown to older servers; pg_restore goes on
     * after this error but exits with code 1.
     */
    static boolean onlyHarmlessErrors(String stderr) {
        List<String> errors = stderr.lines().filter(line -> line.startsWith("pg_restore: error:")).toList();
        return !errors.isEmpty() && errors.stream().allMatch(line -> line.contains("\"transaction_timeout\""));
    }

    @Override
    public boolean supportsSelectiveRestore() {
        return true;
    }

    @Override
    public boolean supportsIncremental() {
        return true;
    }

    @Override
    public String fileExtension(DatabaseConfig database, BackupType type) {
        if (!database.isIncremental()) {
            return "dump";
        }
        return type == BackupType.FULL ? "base.tar" : "incr.tar";
    }

    @Override
    public DumpResult backupChanges(ChangesRequest request, Path outputFile) {
        if (!DumpResult.PHYSICAL.equals(request.method()) || request.fromState() == null) {
            throw new DbBackupException("The previous backup is not a physical backup with a backup manifest: take a "
                    + "full backup");
        }
        return baseBackup(request.database(), request.fromState(), outputFile);
    }

    /**
     * Physical copy of the whole server with {@code pg_basebackup} (plain format, WAL streamed),
     * incremental when {@code previousManifest} is set (PostgreSQL 17+ with {@code summarize_wal}).
     * The data directory is bundled in a tar file and its {@code backup_manifest} becomes the state
     * file the next incremental backup needs.
     */
    private DumpResult baseBackup(DatabaseConfig db, Path previousManifest, Path outputFile) {
        Path dir = outputFile.resolveSibling(outputFile.getFileName() + ".d");
        try {
            List<String> command = new ArrayList<>(List.of(Executables.resolve(db.binPath(), "pg_basebackup")));
            command.addAll(connectionArgs(db, null));
            command.addAll(List.of("--pgdata=" + dir, "--format=plain", "--wal-method=stream", "--checkpoint=fast"));
            if (previousManifest != null) {
                command.add("--incremental=" + previousManifest.toAbsolutePath());
            }
            command.addAll(db.dumpArgs());
            LOG.info("Copying the PostgreSQL server with pg_basebackup ({})",
                    previousManifest == null ? "full" : "incremental");
            runner.run(spec(command, db).timeoutMinutes(db.timeoutMinutes()).build());

            Path manifest = dir.resolve("backup_manifest");
            if (!Files.isRegularFile(manifest)) {
                throw new DbBackupException("pg_basebackup did not write a backup_manifest");
            }
            Path state = outputFile.resolveSibling(outputFile.getFileName() + ".state");
            Files.copy(manifest, state, StandardCopyOption.REPLACE_EXISTING);
            Map<String, String> checkpoint = backupLabel(dir.resolve("backup_label"));
            TarArchives.create(dir, outputFile);
            return new DumpResult(checkpoint, state, DumpResult.PHYSICAL);
        } catch (IOException e) {
            throw new DbBackupException("Physical backup failed: " + e.getMessage(), e);
        } finally {
            FileUtils.deleteRecursively(dir);
        }
    }

    /**
     * Rebuilds a data directory from a physical backup chain into {@code targetDirectory}: the full
     * backup is extracted, incremental ones are merged with {@code pg_combinebackup}, and the result
     * is checked with {@code pg_verifybackup}.
     */
    @Override
    public void restoreChain(RestoreRequest request, Path fullDump, List<Path> changes) {
        if (request.targetDirectory() == null) {
            restoreLogical(request, fullDump, changes);
            return;
        }
        if (!request.tables().isEmpty() || request.targetDatabase() != null) {
            throw new DbBackupException("Physical backups restore the whole server into a directory: --tables and "
                    + "--target-database are not available");
        }
        DatabaseConfig db = request.database();
        Path target = request.targetDirectory().toAbsolutePath();
        List<Path> extracted = new ArrayList<>();
        try {
            if (Files.exists(target)) {
                try (var entries = Files.list(target)) {
                    if (entries.findAny().isPresent()) {
                        throw new DbBackupException("Target directory " + target + " is not empty");
                    }
                }
                Files.delete(target);
            }
            if (changes.isEmpty()) {
                LOG.info("Extracting the base backup into {}", target);
                TarArchives.extract(fullDump, target);
            } else {
                List<String> command = new ArrayList<>(List.of(Executables.resolve(db.binPath(), "pg_combinebackup"),
                        "--output=" + target));
                List<Path> parts = new ArrayList<>();
                parts.add(fullDump);
                parts.addAll(changes);
                for (Path part : parts) {
                    Path dir = Files.createTempDirectory(part.toAbsolutePath().getParent(), "extract-");
                    extracted.add(dir);
                    TarArchives.extract(part, dir);
                    command.add(dir.toString());
                }
                LOG.info("Combining {} backups into {} with pg_combinebackup", parts.size(), target);
                runner.run(spec(command, db).timeoutMinutes(db.timeoutMinutes()).build());
            }
            if (Files.isDirectory(target)) {
                try {
                    Files.setPosixFilePermissions(target,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                } catch (UnsupportedOperationException ignored) {
                    // not a POSIX file system
                }
            }
            runner.run(spec(List.of(Executables.resolve(db.binPath(), "pg_verifybackup"), target.toString()), db)
                    .timeoutMinutes(db.timeoutMinutes()).build());
            LOG.info("Data directory restored and verified in {}. To use it, stop PostgreSQL, make this directory "
                    + "its data directory (owned by the server's user, mode 0700) and start PostgreSQL.", target);
        } catch (IOException e) {
            throw new DbBackupException("Physical restore failed: " + e.getMessage(), e);
        } finally {
            extracted.forEach(FileUtils::deleteRecursively);
        }
    }

    private void restoreLogical(RestoreRequest request, Path fullDump, List<Path> changes) {
        if (!changes.isEmpty()) {
            throw new DbBackupException("Incremental PostgreSQL backups are physical: restore them with --target-dir");
        }
        restore(request, fullDump);
    }

    /** Start position and timeline from a {@code backup_label} file. */
    static Map<String, String> backupLabel(Path label) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("method", "pg_basebackup");
        if (Files.isRegularFile(label)) {
            for (String line : Files.readAllLines(label, StandardCharsets.UTF_8)) {
                if (line.startsWith("START WAL LOCATION:")) {
                    values.put("startLsn", line.substring("START WAL LOCATION:".length()).trim().split(" ")[0]);
                } else if (line.startsWith("START TIMELINE:")) {
                    values.put("timeline", line.substring("START TIMELINE:".length()).trim());
                }
            }
        }
        return values;
    }

    /** Creates the target database when it does not exist, using the {@code postgres} maintenance database. */
    private void ensureDatabaseExists(DatabaseConfig db, String target) {
        String psql = Executables.resolve(db.binPath(), "psql");
        try {
            List<String> check = new ArrayList<>(List.of(psql));
            check.addAll(connectionArgs(db, "postgres"));
            check.addAll(List.of("-X", "-t", "-A", "-c",
                    "SELECT 1 FROM pg_database WHERE datname = " + literal(target)));
            ProcessResult exists = runner.run(spec(check, db).timeout(Duration.ofMinutes(2)).build());
            if (!"1".equals(exists.stdout().strip())) {
                LOG.info("Creating PostgreSQL database '{}'", target);
                List<String> create = new ArrayList<>(List.of(psql));
                create.addAll(connectionArgs(db, "postgres"));
                create.addAll(List.of("-X", "-q", "-c", "CREATE DATABASE " + quote(target)));
                runner.run(spec(create, db).timeout(Duration.ofMinutes(2)).build());
            }
        } catch (DbBackupException e) {
            LOG.warn("Could not check or create database '{}' ({}); restoring anyway", target, e.getMessage());
        }
    }

    private static List<String> connectionArgs(DatabaseConfig db, String database) {
        List<String> args = new ArrayList<>(List.of("--host=" + db.effectiveHost(), "--port=" + db.effectivePort()));
        if (db.username() != null && !db.username().isBlank()) {
            args.add("--username=" + db.username());
        }
        args.add("--no-password");
        if (database != null) {
            args.add("--dbname=" + database);
        }
        return args;
    }

    private static ProcessSpec.Builder spec(List<String> command, DatabaseConfig db) {
        ProcessSpec.Builder builder = ProcessSpec.builder(command)
                .env("PGAPPNAME", "dbbackup")
                .env("PGCONNECT_TIMEOUT", "15")
                .missingHint(HINT);
        if (db.hasPassword()) {
            builder.env("PGPASSWORD", db.password()).secret(db.password());
        }
        return builder;
    }

    static boolean isCustomFormat(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.equals(in.readNBytes(CUSTOM_FORMAT_MAGIC.length), CUSTOM_FORMAT_MAGIC);
        } catch (IOException e) {
            throw new DbBackupException("Cannot read dump file " + file + ": " + e.getMessage(), e);
        }
    }

    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
