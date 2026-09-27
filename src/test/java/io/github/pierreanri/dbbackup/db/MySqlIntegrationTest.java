/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;

/**
 * Runs mysqldump/mysql against a real MySQL or MariaDB server. Enabled when
 * {@code DBBACKUP_IT_MYSQL_HOST} is set (plus optional {@code _PORT}, {@code _USER},
 * {@code _PASSWORD}, and {@code _BIN} for the directory of client tools matching the server; set
 * {@code DBBACKUP_IT_MYSQL_MARIADB=true} for MariaDB tools).
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DBBACKUP_IT_MYSQL_HOST", matches = ".+")
class MySqlIntegrationTest {

    @TempDir
    Path tmp;

    private final ProcessRunner runner = new ProcessRunner();
    private final boolean mariadb = Boolean.parseBoolean(System.getenv("DBBACKUP_IT_MYSQL_MARIADB"));
    private final MySqlAdapter adapter = new MySqlAdapter(runner, mariadb);
    private final List<String> createdDatabases = new ArrayList<>();
    private DatabaseConfig db;

    @BeforeEach
    void createDatabase() {
        String name = "dbbackup_it_" + UUID.randomUUID().toString().substring(0, 8);
        db = DatabaseConfig.of("it", mariadb ? DatabaseType.MARIADB : DatabaseType.MYSQL)
                .withHost(System.getenv("DBBACKUP_IT_MYSQL_HOST"))
                .withPort(Integer.valueOf(env("DBBACKUP_IT_MYSQL_PORT", "3306")))
                .withCredentials(env("DBBACKUP_IT_MYSQL_USER", "root"), System.getenv("DBBACKUP_IT_MYSQL_PASSWORD"))
                .withDatabase(name)
                .withBinPath(System.getenv("DBBACKUP_IT_MYSQL_BIN"));
        createdDatabases.add(name);
        sql(null, "CREATE DATABASE " + name);
        sql(name, "CREATE TABLE products (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50), price DECIMAL(8,2));"
                + "CREATE VIEW cheap AS SELECT * FROM products WHERE price < 10;"
                + "INSERT INTO products(name, price) VALUES ('pen', 1.50), ('book', 12.00), ('mug', 7.25);");
    }

    @AfterEach
    void dropDatabases() {
        for (String name : createdDatabases) {
            try {
                sql(null, "DROP DATABASE IF EXISTS " + name);
            } catch (RuntimeException ignored) {
                // best effort cleanup
            }
        }
    }

    @Test
    void testsConnection() {
        assertThat(adapter.testConnection(db)).matches("(MySQL|MariaDB) .+");
    }

    @Test
    void backsUpAndRestoresIntoNewDatabase() throws Exception {
        Path dump = tmp.resolve("db.sql");
        adapter.backup(BackupRequest.full(db), dump);
        assertThat(Files.readString(dump)).contains("CREATE TABLE `products`");

        String restored = db.database() + "_restored";
        createdDatabases.add(restored);
        adapter.restore(new RestoreRequest(db, restored, db.database(), List.of(), false), dump);

        assertThat(sql(restored, "SELECT COUNT(*) FROM products")).isEqualTo("3");
        assertThat(sql(restored, "SELECT COUNT(*) FROM cheap")).isEqualTo("2");
    }

    @Test
    void selectiveBackupOnlyContainsRequestedTables() throws Exception {
        sql(db.database(), "CREATE TABLE other (id INT)");
        Path dump = tmp.resolve("products.sql");
        adapter.backup(new BackupRequest(db, BackupScope.FULL, List.of("products")), dump);

        assertThat(Files.readString(dump)).contains("`products`").doesNotContain("CREATE TABLE `other`");
    }

    @Test
    void incrementalChainsReplayTheBinaryLogs() {
        DatabaseConfig incremental = db.withIncremental(true);
        String other = db.database() + "_other";
        createdDatabases.add(other);
        sql(null, "CREATE DATABASE " + other);
        sql(other, "CREATE TABLE products (id INT PRIMARY KEY, name VARCHAR(50), price DECIMAL(8,2))");

        Path full = tmp.resolve("full.sql");
        DumpResult fullResult = adapter.backup(BackupRequest.full(incremental), full);
        assertThat(fullResult.checkpoint()).containsKeys("binlogFile", "binlogPosition");

        sql(db.database(), "INSERT INTO products(name, price) VALUES ('lamp', 20.00), ('desk', 150.00)");
        sql(other, "INSERT INTO products VALUES (1, 'not mine', 1.00)");
        Path incr1 = tmp.resolve("incr1.binlog.tar");
        DumpResult first = adapter.backupChanges(new ChangesRequest(incremental, BackupType.INCREMENTAL,
                fullResult.checkpoint(), null, "logical"), incr1);

        sql(db.database(), "UPDATE products SET price = price * 2; DELETE FROM products WHERE name = 'pen';"
                + "CREATE TABLE audit (msg TEXT); INSERT INTO audit VALUES ('created after the full backup')");
        Path incr2 = tmp.resolve("incr2.binlog.tar");
        adapter.backupChanges(new ChangesRequest(incremental, BackupType.INCREMENTAL, first.checkpoint(), null,
                "logical"), incr2);
        Path diff = tmp.resolve("diff.binlog.tar");
        adapter.backupChanges(new ChangesRequest(incremental, BackupType.DIFFERENTIAL, fullResult.checkpoint(), null,
                "logical"), diff);

        String expected = sql(db.database(), "SELECT GROUP_CONCAT(CONCAT(name, '=', price) ORDER BY id) FROM products");
        assertThat(expected).isEqualTo("book=24.00,mug=14.50,lamp=40.00,desk=300.00");

        String viaIncrementals = db.database() + "_r1";
        createdDatabases.add(viaIncrementals);
        adapter.restoreChain(new RestoreRequest(incremental, viaIncrementals, db.database(), List.of(), false), full,
                List.of(incr1, incr2));
        assertThat(sql(viaIncrementals, "SELECT GROUP_CONCAT(CONCAT(name, '=', price) ORDER BY id) FROM products"))
                .isEqualTo(expected);
        assertThat(sql(viaIncrementals, "SELECT msg FROM audit")).isEqualTo("created after the full backup");

        String viaDifferential = db.database() + "_r2";
        createdDatabases.add(viaDifferential);
        adapter.restoreChain(new RestoreRequest(incremental, viaDifferential, db.database(), List.of(), false), full,
                List.of(diff));
        assertThat(sql(viaDifferential, "SELECT GROUP_CONCAT(CONCAT(name, '=', price) ORDER BY id) FROM products"))
                .isEqualTo(expected);
        assertThat(sql(other, "SELECT COUNT(*) FROM products")).isEqualTo("1");
    }

    @Test
    void reportsAuthenticationErrorsWithoutLeakingPassword() {
        DatabaseConfig wrong = db.withCredentials(db.username(), "definitely-wrong-password");
        assertThatThrownBy(() -> adapter.testConnection(wrong))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("Access denied")
                .hasMessageNotContaining("definitely-wrong-password");
    }

    private String sql(String database, String statement) {
        Path options = MySqlAdapter.optionFile(db);
        try {
            List<String> command = new ArrayList<>(List.of(mariadb ? "mariadb" : "mysql",
                    "--defaults-extra-file=" + options, "--batch", "--skip-column-names", "-e", statement));
            if (database != null) {
                command.add(database);
            }
            return runner.run(ProcessSpec.builder(command).build()).stdout().strip();
        } finally {
            SecretFiles.deleteQuietly(options);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
