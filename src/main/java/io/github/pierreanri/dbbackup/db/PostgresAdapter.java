package io.github.pierreanri.dbbackup.db;

import java.io.IOException;
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

/**
 * PostgreSQL support based on {@code pg_dump} (custom format), {@code pg_restore} and {@code psql}.
 * The password is passed through the {@code PGPASSWORD} environment variable.
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
    public void backup(BackupRequest request, Path outputFile) {
        DatabaseConfig db = request.database();
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
        runner.run(spec(command, db).timeoutMinutes(db.timeoutMinutes()).build());
    }

    @Override
    public boolean supportsSelectiveRestore() {
        return true;
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
