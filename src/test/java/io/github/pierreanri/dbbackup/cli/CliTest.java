package io.github.pierreanri.dbbackup.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import io.github.pierreanri.dbbackup.config.ConfigLoader;
import io.github.pierreanri.dbbackup.config.LoadedConfig;
import picocli.CommandLine;

/**
 * Drives the real command line with a SQLite database and local storage.
 */
class CliTest {

    @TempDir
    Path tmp;

    private Path config;
    private Path dbFile;
    private String out;
    private String err;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        dbFile = tmp.resolve("app.db");
        sql(dbFile, "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)",
                "INSERT INTO users(name) VALUES ('ada'), ('grace')", "CREATE TABLE audit (msg TEXT)");
        config = Files.writeString(tmp.resolve("dbbackup.yml"), """
                defaults:
                  compression: gzip
                  storage: [primary]
                  retention:
                    keepLast: 2
                databases:
                  app:
                    type: sqlite
                    file: %s
                  broken:
                    type: sqlite
                    file: %s
                storage:
                  primary:
                    type: local
                    path: %s
                  mirror:
                    type: local
                    path: %s
                schedules:
                  - name: nightly
                    database: app
                    cron: "0 2 * * *"
                    storage: [mirror]
                    compression: bzip2
                  - name: paused
                    database: app
                    cron: "@weekly"
                    enabled: false
                logging:
                  dir: %s
                """.formatted(dbFile, tmp.resolve("missing.db"), tmp.resolve("primary"), tmp.resolve("mirror"),
                tmp.resolve("logs")));
    }

    @AfterEach
    void resetLogging() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        new ContextInitializer(context).autoConfig();
        RestoreCommand.input = new BufferedReader(new java.io.InputStreamReader(System.in));
    }

    private int run(String... args) {
        StringWriter outWriter = new StringWriter();
        StringWriter errWriter = new StringWriter();
        CommandLine cmd = RootCommand.newCommandLine();
        cmd.setOut(new PrintWriter(outWriter, true));
        cmd.setErr(new PrintWriter(errWriter, true));
        List<String> all = new ArrayList<>(List.of("--config", config.toString(), "--quiet"));
        all.addAll(List.of(args));
        int exit = cmd.execute(all.toArray(String[]::new));
        out = outWriter.toString();
        err = errWriter.toString();
        return exit;
    }

    @Test
    void backupListRestoreAndHistory() throws SQLException {
        assertThat(run("backup", "app")).isZero();
        assertThat(out).contains("Backup app-").contains("completed").contains("sha256:")
                .contains(tmp.resolve("primary").toString());

        assertThat(run("list")).isZero();
        assertThat(out).contains("ID").contains("app-").contains("sqlite").contains("gzip").contains("primary");

        sql(dbFile, "DELETE FROM users");
        assertThat(run("restore", "latest", "--db", "app", "--yes")).isZero();
        assertThat(out).contains("Restored app-");
        assertThat(count(dbFile, "users")).isEqualTo(2);

        assertThat(run("history")).isZero();
        assertThat(out).contains("backup").contains("restore").contains("SUCCESS");
        assertThat(tmp.resolve("logs/dbbackup.log")).exists();
        assertThat(tmp.resolve("logs/history.jsonl")).exists();
    }

    @Test
    void backupToSeveralTargetsAsJson() {
        assertThat(run("backup", "app", "--storage", "primary,mirror", "--compression", "xz", "--json")).isZero();
        assertThat(out).contains("\"compression\" : \"xz\"").contains("\"databaseType\" : \"sqlite\"");
        assertThat(files(tmp.resolve("mirror/app"))).anyMatch(name -> name.endsWith(".db.xz"));
    }

    @Test
    void failuresReturnNonZeroAndAreRecorded() {
        assertThat(run("backup", "app", "broken")).isEqualTo(1);
        assertThat(out).contains("Backup app-");
        assertThat(err).contains("Backup of 'broken' failed").contains("SQLite database file not found");

        assertThat(run("history", "--failed")).isZero();
        assertThat(out).contains("broken").contains("FAILED").doesNotContain("SUCCESS");
    }

    @Test
    void retentionAndPrune() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            assertThat(run("backup", "app", "--no-retention")).isZero();
            Thread.sleep(1100);
        }
        assertThat(run("prune", "--dry-run")).isZero();
        assertThat(out).contains("Would delete app-").contains("1 backup(s) would be deleted");

        assertThat(run("prune", "--keep-last", "1")).isZero();
        assertThat(out).contains("2 backup(s) deleted");
        assertThat(run("list", "app", "--json")).isZero();
        assertThat(out.split("\"id\"", -1)).hasSize(2);
    }

    @Test
    void restoreSelectedTablesIntoAnotherFileAfterConfirmation() throws SQLException {
        run("backup", "app");
        RestoreCommand.input = new BufferedReader(new StringReader("y\n"));
        Path copy = tmp.resolve("copy.db");

        assertThat(run("restore", "latest", "--db", "app", "--target-database", copy.toString(), "-t", "users"))
                .isZero();
        assertThat(out).contains("Continue? [y/N]").contains("tables: users");
        assertThat(count(copy, "users")).isEqualTo(2);
    }

    @Test
    void restoreCanBeCancelled() {
        run("backup", "app");
        RestoreCommand.input = new BufferedReader(new StringReader("n\n"));
        assertThat(run("restore", "latest", "--db", "app")).isEqualTo(1);
        assertThat(out).contains("Restore cancelled.");
    }

    @Test
    void restoreByIdFindsTheProfileFromTheBackup() throws SQLException {
        run("backup", "app", "--json");
        String id = out.replaceAll("(?s).*\"id\" : \"([^\"]+)\".*", "$1");
        sql(dbFile, "DELETE FROM users");

        assertThat(run("restore", id, "-y")).isZero();
        assertThat(count(dbFile, "users")).isEqualTo(2);
    }

    @Test
    void adHocBackupWithoutConfigFile() throws IOException {
        Files.delete(config);
        Files.writeString(config, "logging:\n  dir: " + tmp.resolve("logs") + "\n");
        Path out1 = tmp.resolve("adhoc");

        assertThat(run("backup", "--db-type", "sqlite", "--db-file", dbFile.toString(), "-o", out1.toString()))
                .isZero();
        assertThat(files(out1.resolve("app"))).anyMatch(name -> name.startsWith("app-") && name.endsWith(".db.gz"));
    }

    @Test
    void testConnectionReportsEachDatabase() {
        assertThat(run("test-connection")).isEqualTo(1);
        assertThat(out).contains("OK    app").contains("FAIL  broken");
        assertThat(run("test-connection", "app")).isZero();
    }

    @Test
    void testStorageWritesAProbe() {
        assertThat(run("test-storage")).isZero();
        assertThat(out).contains("OK    primary").contains("OK    mirror");
        assertThat(files(tmp.resolve("primary"))).isEmpty();
    }

    @Test
    void configValidateAndInit() throws IOException {
        assertThat(run("config", "validate")).isZero();
        assertThat(out).contains("is valid: 2 database(s), 2 storage target(s), 2 schedule(s)");

        Path generated = tmp.resolve("new/config.yml");
        assertThat(run("config", "init", generated.toString())).isZero();
        assertThat(generated).exists();
        assertThat(run("config", "init", generated.toString())).isEqualTo(1);
        assertThat(err).contains("already exists");

        LoadedConfig example = new ConfigLoader(name -> "x", tmp, tmp).loadFile(generated);
        assertThat(example.config().databases()).containsKeys("app-postgres", "shop-mysql", "events-mongo", "cache-sqlite");
    }

    @Test
    void reportsConfigurationErrors() throws IOException {
        Files.writeString(config, "databases:\n  x:\n    type: nope\n");
        assertThat(run("list")).isEqualTo(1);
        assertThat(err).contains("Error: Invalid config").contains("unsupported database type 'nope'");
    }

    @Test
    void reportsUnknownDatabases() {
        assertThat(run("backup", "nope")).isEqualTo(1);
        assertThat(err).contains("Unknown database 'nope'").contains("Configured databases: app, broken");
    }

    @Test
    void scheduleListRunAndCron() {
        assertThat(run("schedule", "list")).isZero();
        assertThat(out).contains("nightly").contains("0 2 * * *").contains("paused").contains("no");

        assertThat(run("schedule", "run", "nightly")).isZero();
        assertThat(out).contains("Backup app-").contains("completed");
        assertThat(files(tmp.resolve("mirror/app"))).anyMatch(name -> name.endsWith(".db.bz2"));
        assertThat(run("history")).isZero();
        assertThat(out).contains("schedule:nightly");

        assertThat(run("schedule", "cron", "--command", "dbbackup")).isZero();
        assertThat(out).contains("0 2 * * * dbbackup --quiet schedule run nightly >> ")
                .contains("cron.log").doesNotContain("paused");

        assertThat(run("schedule", "run", "nope")).isEqualTo(1);
        assertThat(err).contains("Unknown schedule 'nope'");
    }

    @Test
    void globalOptionsWorkAfterTheSubcommand() {
        StringWriter outWriter = new StringWriter();
        CommandLine cmd = RootCommand.newCommandLine();
        cmd.setOut(new PrintWriter(outWriter, true));
        int exit = cmd.execute("list", "--config", config.toString(), "-q");
        assertThat(exit).isZero();
        assertThat(outWriter.toString()).contains("No backups found.");
    }

    private static List<String> files(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void sql(Path db, String... statements) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
        }
    }

    private static int count(Path db, String table) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
