/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.util.FileUtils;
import io.github.pierreanri.dbbackup.util.Mappers;
import io.github.pierreanri.dbbackup.util.TarArchives;

/**
 * MySQL and MariaDB support based on {@code mysqldump}/{@code mariadb-dump} and the
 * {@code mysql}/{@code mariadb} client.
 *
 * <p>Credentials are written to a private temporary option file passed with
 * {@code --defaults-extra-file}, so the password never shows up in the process list.
 *
 * <p>Incremental backups rely on the binary log: full backups record the binary log position of
 * their snapshot, and incremental/differential backups copy the binary logs written since the
 * position of their parent. Restores load the full dump and replay those logs.
 */
public class MySqlAdapter implements DatabaseAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlAdapter.class);
    private static final String HINT = "Install the MySQL or MariaDB client tools (mysqldump, mysql) "
            + "or set 'binPath' for this database.";

    static final String BINLOG_FILE = "binlogFile";
    static final String BINLOG_POSITION = "binlogPosition";
    private static final String DESCRIPTOR = "dbbackup-binlog.json";
    private static final Pattern POSITION = Pattern.compile("CHANGE (?:REPLICATION SOURCE|MASTER) TO "
            + "(?:SOURCE|MASTER)_LOG_FILE='([^']+)',\\s*(?:SOURCE|MASTER)_LOG_POS=(\\d+)");

    private final ProcessRunner runner;
    private final boolean mariadb;
    private final Map<String, String> helpCache = new java.util.concurrent.ConcurrentHashMap<>();

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
            boolean startsChain = db.isIncremental() && request.scope() == BackupScope.FULL
                    && request.tables().isEmpty();
            if (startsChain) {
                // records the binary log position matching the snapshot as a comment in the dump
                command.add(supports(command.get(0), "--source-data") ? "--source-data=2" : "--master-data=2");
            }
            command.add("--result-file=" + outputFile.toAbsolutePath());
            command.addAll(db.dumpArgs());
            command.add(db.database());
            command.addAll(request.tables());

            LOG.info("Dumping {} database '{}' with {}", label(), db.database(), Path.of(command.get(0)).getFileName());
            ProcessSpec dump = spec(command, db).timeoutMinutes(db.timeoutMinutes()).build();
            ProcessResult result = runner.execute(dump);
            if (result.exitCode() != 0) {
                if (startsChain && result.stderr().matches("(?s).*(MASTER|BINARY LOG) STATUS.*syntax.*")) {
                    throw new DbBackupException("mysqldump cannot read the binary log position of this server: "
                            + "incremental backups need client tools from the same release series as the server "
                            + "(MySQL 8.4 removed SHOW MASTER STATUS, older servers lack SHOW BINARY LOG STATUS). "
                            + "Set 'binPath' to matching tools. " + ProcessRunner.failure(dump, result).getMessage());
                }
                throw ProcessRunner.failure(dump, result);
            }
            if (!startsChain) {
                return DumpResult.NONE;
            }
            String[] position = binlogPosition(outputFile);
            LOG.info("Binary log position: {}:{}", position[0], position[1]);
            return DumpResult.of(Map.of(BINLOG_FILE, position[0], BINLOG_POSITION, position[1]));
        } finally {
            SecretFiles.deleteQuietly(options);
        }
    }

    @Override
    public boolean supportsIncremental() {
        return true;
    }

    @Override
    public String fileExtension(DatabaseConfig database, BackupType type) {
        return type == BackupType.FULL ? "sql" : "binlog.tar";
    }

    /**
     * Copies the binary logs written since the checkpoint: the current log is rotated with
     * {@code FLUSH BINARY LOGS}, then every closed log from the checkpoint's file on is fetched with
     * {@code mysqlbinlog --read-from-remote-server --raw} and bundled with a small descriptor.
     */
    @Override
    public DumpResult backupChanges(ChangesRequest request, Path outputFile) {
        DatabaseConfig db = request.database();
        String fromFile = request.fromCheckpoint().get(BINLOG_FILE);
        String fromPosition = request.fromCheckpoint().get(BINLOG_POSITION);
        if (fromFile == null || fromPosition == null) {
            throw new DbBackupException("The previous backup has no binary log position: take a full backup");
        }
        Path options = optionFile(db);
        Path dir = outputFile.resolveSibling(outputFile.getFileName() + ".d");
        try {
            query(db, options, "FLUSH BINARY LOGS");
            List<String> logs = query(db, options, "SHOW BINARY LOGS").lines()
                    .map(line -> line.split("\t")[0].strip()).filter(name -> !name.isEmpty()).toList();
            int from = logs.indexOf(fromFile);
            if (from < 0) {
                throw new DbBackupException("Binary log " + fromFile + " is no longer available on the server "
                        + "(purged?): take a full backup");
            }
            List<String> files = logs.subList(from, logs.size() - 1);
            String nextFile = logs.get(logs.size() - 1);

            Files.createDirectories(dir.resolve("binlogs"));
            List<String> command = new ArrayList<>(List.of(binlogTool(db), "--defaults-extra-file=" + options,
                    "--read-from-remote-server", "--raw", "--result-file=" + dir.resolve("binlogs") + "/"));
            command.addAll(files);
            LOG.info("Copying binary logs {} (from position {})", String.join(", ", files), fromPosition);
            runner.run(spec(command, db).timeoutMinutes(db.timeoutMinutes()).build());

            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("format", "mysql-binlog");
            descriptor.put("database", db.database());
            descriptor.put("startFile", fromFile);
            descriptor.put("startPosition", Long.parseLong(fromPosition));
            descriptor.put("files", files);
            Mappers.json().writerWithDefaultPrettyPrinter().writeValue(dir.resolve(DESCRIPTOR).toFile(), descriptor);
            TarArchives.create(dir, outputFile);
            return DumpResult.of(Map.of(BINLOG_FILE, nextFile, BINLOG_POSITION, "4"));
        } catch (IOException e) {
            throw new DbBackupException("Copying the binary logs failed: " + e.getMessage(), e);
        } finally {
            SecretFiles.deleteQuietly(options);
            FileUtils.deleteRecursively(dir);
        }
    }

    /**
     * Restores the full dump, then replays the binary logs of each change file for the backed up
     * database, renamed to the target database when it differs.
     */
    @Override
    public void restoreChain(RestoreRequest request, Path fullDump, List<Path> changes) {
        restore(request, fullDump);
        DatabaseConfig db = request.database();
        String target = request.effectiveTargetDatabase();
        for (Path change : changes) {
            Path dir = change.resolveSibling(change.getFileName() + ".d");
            Path options = optionFile(db);
            try {
                TarArchives.extract(change, dir);
                @SuppressWarnings("unchecked")
                Map<String, Object> descriptor = Mappers.json().readValue(dir.resolve(DESCRIPTOR).toFile(), Map.class);
                String source = String.valueOf(descriptor.get("database"));
                @SuppressWarnings("unchecked")
                List<String> files = (List<String>) descriptor.get("files");

                String tool = binlogTool(db);
                List<String> decode = new ArrayList<>(List.of(tool));
                if (supports(tool, "--skip-gtids")) {
                    decode.add("--skip-gtids");
                }
                if (!source.equals(target)) {
                    decode.add("--rewrite-db=" + source + "->" + target);
                }
                decode.add("--database=" + target);
                decode.add("--start-position=" + descriptor.get("startPosition"));
                Path sql = dir.resolve("replay.sql");
                decode.add("--result-file=" + sql);
                for (String file : files) {
                    decode.add(dir.resolve("binlogs").resolve(file).toString());
                }
                LOG.info("Replaying binary logs {} into '{}'", String.join(", ", files), target);
                runner.run(spec(decode, db).timeoutMinutes(db.timeoutMinutes()).build());

                List<String> apply = new ArrayList<>(List.of(client(db), "--defaults-extra-file=" + options,
                        "--default-character-set=utf8mb4", target));
                runner.run(spec(apply, db).stdin(sql).timeoutMinutes(db.timeoutMinutes()).build());
            } catch (IOException e) {
                throw new DbBackupException("Cannot read " + change.getFileName() + ": " + e.getMessage(), e);
            } finally {
                SecretFiles.deleteQuietly(options);
                FileUtils.deleteRecursively(dir);
            }
        }
    }

    /** Reads the position recorded by {@code --source-data=2} / {@code --master-data=2} at the top of a dump. */
    static String[] binlogPosition(Path dump) {
        try (BufferedReader reader = Files.newBufferedReader(dump, StandardCharsets.UTF_8)) {
            String line;
            for (int i = 0; i < 500 && (line = reader.readLine()) != null; i++) {
                Matcher matcher = POSITION.matcher(line);
                if (matcher.find()) {
                    return new String[] {matcher.group(1), matcher.group(2)};
                }
            }
        } catch (IOException e) {
            throw new DbBackupException("Cannot read " + dump.getFileName() + ": " + e.getMessage(), e);
        }
        throw new DbBackupException("The dump does not contain a binary log position: incremental backups need "
                + "binary logging (log_bin) enabled on the server");
    }

    private String query(DatabaseConfig db, Path options, String sql) {
        return runner.run(spec(List.of(client(db), "--defaults-extra-file=" + options, "--batch", "--skip-column-names",
                "-e", sql), db).timeout(Duration.ofMinutes(2)).build()).stdout();
    }

    /** Whether a client tool supports an option, according to its {@code --help}. */
    private boolean supports(String tool, String option) {
        return helpCache.computeIfAbsent(tool, t -> {
            try {
                return runner.run(ProcessSpec.builder(List.of(t, "--help")).missingHint(HINT)
                        .timeout(Duration.ofMinutes(1)).build()).stdout();
            } catch (DbBackupException e) {
                return "";
            }
        }).contains(option);
    }

    private String binlogTool(DatabaseConfig db) {
        return mariadb ? Executables.resolve(db.binPath(), "mariadb-binlog", "mysqlbinlog")
                : Executables.resolve(db.binPath(), "mysqlbinlog", "mariadb-binlog");
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
