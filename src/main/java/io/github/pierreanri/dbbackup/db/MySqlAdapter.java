package io.github.pierreanri.dbbackup.db;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;

/**
 * MySQL and MariaDB support based on {@code mysqldump}/{@code mariadb-dump} and the
 * {@code mysql}/{@code mariadb} client.
 *
 * <p>Credentials are written to a private temporary option file passed with
 * {@code --defaults-extra-file}, so the password never shows up in the process list.
 */
public class MySqlAdapter implements DatabaseAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlAdapter.class);
    private static final String HINT = "Install the MySQL or MariaDB client tools (mysqldump, mysql) "
            + "or set 'binPath' for this database.";

    private final ProcessRunner runner;
    private final boolean mariadb;

    public MySqlAdapter(ProcessRunner runner, boolean mariadb) {
        this.runner = runner;
        this.mariadb = mariadb;
    }

    @Override
    public String testConnection(DatabaseConfig db) {
        Path options = optionFile(db);
        try {
            List<String> command = new ArrayList<>(List.of(client(db), "--defaults-extra-file=" + options,
                    "--batch", "--skip-column-names", "-e", "SELECT VERSION()"));
            if (db.database() != null) {
                command.add(db.database());
            }
            ProcessResult result = runner.run(spec(command, db).timeout(Duration.ofMinutes(2)).build());
            String version = result.stdout().strip();
            return (version.toLowerCase().contains("mariadb") ? "MariaDB " : "MySQL ") + version;
        } finally {
            SecretFiles.deleteQuietly(options);
        }
    }

    @Override
    public DumpResult backup(BackupRequest request, Path outputFile) {
        DatabaseConfig db = request.database();
        Path options = optionFile(db);
        try {
            List<String> command = new ArrayList<>(List.of(dump(db), "--defaults-extra-file=" + options,
                    "--single-transaction", "--quick", "--hex-blob", "--no-tablespaces",
                    "--default-character-set=utf8mb4"));
            switch (request.scope()) {
                case FULL -> command.addAll(List.of("--routines", "--triggers", "--events"));
                case SCHEMA_ONLY -> command.addAll(List.of("--no-data", "--routines", "--triggers", "--events"));
                case DATA_ONLY -> command.addAll(List.of("--no-create-info", "--skip-triggers"));
            }
            command.add("--result-file=" + outputFile.toAbsolutePath());
            command.addAll(db.dumpArgs());
            command.add(db.database());
            command.addAll(request.tables());

            LOG.info("Dumping {} database '{}' with {}", label(), db.database(), Path.of(command.get(0)).getFileName());
            runner.run(spec(command, db).timeoutMinutes(db.timeoutMinutes()).build());
            return DumpResult.NONE;
        } finally {
            SecretFiles.deleteQuietly(options);
        }
    }

    @Override
    public void restore(RestoreRequest request, Path dumpFile) {
        if (!request.tables().isEmpty()) {
            throw new DbBackupException(label() + " backups cannot be restored table by table; "
                    + "take a backup with --tables instead");
        }
        DatabaseConfig db = request.database();
        String target = request.effectiveTargetDatabase();
        if (target == null || target.isBlank()) {
            throw new DbBackupException("No target database: set 'database' on the profile or use --target-database");
        }
        Path options = optionFile(db);
        try {
            runner.run(spec(List.of(client(db), "--defaults-extra-file=" + options, "--batch", "-e",
                    "CREATE DATABASE IF NOT EXISTS " + quote(target)), db).timeout(Duration.ofMinutes(2)).build());

            List<String> command = new ArrayList<>(List.of(client(db), "--defaults-extra-file=" + options,
                    "--default-character-set=utf8mb4"));
            command.addAll(db.restoreArgs());
            command.add(target);
            LOG.info("Restoring {} database '{}' from {}", label(), target, dumpFile.getFileName());
            runner.run(spec(command, db).stdin(dumpFile).timeoutMinutes(db.timeoutMinutes()).build());
        } finally {
            SecretFiles.deleteQuietly(options);
        }
    }

    private String label() {
        return mariadb ? "MariaDB" : "MySQL";
    }

    private String dump(DatabaseConfig db) {
        return mariadb ? Executables.resolve(db.binPath(), "mariadb-dump", "mysqldump")
                : Executables.resolve(db.binPath(), "mysqldump", "mariadb-dump");
    }

    private String client(DatabaseConfig db) {
        return mariadb ? Executables.resolve(db.binPath(), "mariadb", "mysql")
                : Executables.resolve(db.binPath(), "mysql", "mariadb");
    }

    private static ProcessSpec.Builder spec(List<String> command, DatabaseConfig db) {
        return ProcessSpec.builder(command).secret(db.password()).missingHint(HINT);
    }

    /** Writes the connection settings to a private option file understood by all MySQL/MariaDB tools. */
    static Path optionFile(DatabaseConfig db) {
        StringBuilder content = new StringBuilder("[client]\n");
        content.append("host=").append(optionValue(db.effectiveHost())).append('\n');
        content.append("port=").append(db.effectivePort()).append('\n');
        if (db.username() != null && !db.username().isBlank()) {
            content.append("user=").append(optionValue(db.username())).append('\n');
        }
        if (db.hasPassword()) {
            content.append("password=").append(optionValue(db.password())).append('\n');
        }
        return SecretFiles.create("dbbackup-mysql-", ".cnf", content.toString());
    }

    /** Quotes an option file value; backslash escapes are interpreted inside quotes. */
    static String optionValue(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
