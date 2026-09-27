/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;

class MySqlAdapterTest {

    @TempDir
    Path tmp;

    private final RecordingRunner runner = new RecordingRunner();
    private final MySqlAdapter adapter = new MySqlAdapter(runner, false);
    private final DatabaseConfig db = DatabaseConfig.of("shop", DatabaseType.MYSQL)
            .withHost("db.example.com")
            .withCredentials("backup", "pa\"ss#w\\rd")
            .withDatabase("shop");

    @Test
    void dumpsWithCredentialsInPrivateOptionFile() {
        Path out = tmp.resolve("shop.sql");
        adapter.backup(new BackupRequest(db, BackupScope.FULL, List.of("orders", "customers")), out);

        List<String> cmd = runner.lastCommand();
        assertThat(cmd.get(0)).endsWith("mysqldump");
        assertThat(cmd.get(1)).startsWith("--defaults-extra-file=");
        assertThat(cmd).contains("--single-transaction", "--routines", "--triggers", "--events",
                "--result-file=" + out.toAbsolutePath());
        assertThat(cmd.subList(cmd.size() - 3, cmd.size())).containsExactly("shop", "orders", "customers");
        assertThat(String.join(" ", cmd)).doesNotContain("pa\"ss");
        assertThat(runner.last().secrets()).contains("pa\"ss#w\\rd");

        assertThat(runner.credentialFiles.get("--defaults-extra-file="))
                .contains("[client]")
                .contains("host=\"db.example.com\"")
                .contains("port=3306")
                .contains("user=\"backup\"")
                .contains("password=\"pa\"ss#w\\\\rd\"");
        assertThat(Path.of(cmd.get(1).substring("--defaults-extra-file=".length()))).doesNotExist();
    }

    @Test
    void mapsScopes() {
        adapter.backup(new BackupRequest(db, BackupScope.SCHEMA_ONLY, List.of()), tmp.resolve("a.sql"));
        assertThat(runner.lastCommand()).contains("--no-data");

        adapter.backup(new BackupRequest(db, BackupScope.DATA_ONLY, List.of()), tmp.resolve("b.sql"));
        assertThat(runner.lastCommand()).contains("--no-create-info", "--skip-triggers").doesNotContain("--routines");
    }

    @Test
    void restoresThroughStdinIntoTargetDatabase() {
        Path dump = tmp.resolve("shop.sql");
        adapter.restore(new RestoreRequest(db, "shop_copy", "shop", List.of(), false), dump);

        assertThat(runner.specs).hasSize(2);
        assertThat(runner.specs.get(0).command()).contains("CREATE DATABASE IF NOT EXISTS `shop_copy`");
        ProcessSpec restore = runner.last();
        assertThat(restore.command().get(0)).endsWith("mysql");
        assertThat(restore.command()).endsWith("shop_copy");
        assertThat(restore.stdin()).isEqualTo(dump);
    }

    @Test
    void refusesSelectiveRestore() {
        assertThat(adapter.supportsSelectiveRestore()).isFalse();
        assertThatThrownBy(() -> adapter.restore(new RestoreRequest(db, null, "shop", List.of("orders"), false),
                tmp.resolve("x.sql")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("table by table");
    }

    @Test
    void reportsServerVersion() {
        runner.respond("10.11.6-MariaDB-0ubuntu0.24.04.1\n");
        assertThat(new MySqlAdapter(runner, true).testConnection(db)).isEqualTo("MariaDB 10.11.6-MariaDB-0ubuntu0.24.04.1");
        assertThat(runner.lastCommand()).contains("SELECT VERSION()");
    }

    @Test
    void fullBackupsOfIncrementalDatabasesRecordTheBinlogPosition() throws IOException {
        Path out = Files.writeString(tmp.resolve("shop.sql"), "-- MySQL dump\n"
                + "-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='binlog.000012', SOURCE_LOG_POS=4711;\n");
        runner.respond("  --source-data[=#]   This causes the binary log position");

        DumpResult result = adapter.backup(BackupRequest.full(db.withIncremental(true)), out);

        assertThat(runner.specs.get(0).command()).containsExactly(runner.specs.get(0).command().get(0), "--help");
        assertThat(runner.lastCommand()).contains("--source-data=2");
        assertThat(result.checkpoint()).containsEntry("binlogFile", "binlog.000012")
                .containsEntry("binlogPosition", "4711");
        assertThat(MySqlAdapter.binlogPosition(Files.writeString(tmp.resolve("maria.sql"),
                "-- CHANGE MASTER TO MASTER_LOG_FILE='mysql-bin.000002', MASTER_LOG_POS=328;\n")))
                .containsExactly("mysql-bin.000002", "328");
    }

    @Test
    void explainsMismatchedClientTools() {
        runner.respond("--source-data");
        runner.onRun = spec -> {
            if (spec.command().stream().anyMatch(arg -> arg.startsWith("--result-file="))) {
                runner.nextResult = new ProcessResult(2, "", "mysqldump: Couldn't execute 'SHOW MASTER STATUS': You have "
                        + "an error in your SQL syntax; check the manual (1064)");
            }
        };
        assertThatThrownBy(() -> adapter.backup(BackupRequest.full(db.withIncremental(true)), tmp.resolve("x.sql")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("same release series as the server")
                .hasMessageContaining("SHOW MASTER STATUS");
    }

    @Test
    void failsWhenTheDumpHasNoBinlogPosition() throws IOException {
        Path out = Files.writeString(tmp.resolve("shop.sql"), "-- MySQL dump\n");
        assertThatThrownBy(() -> adapter.backup(BackupRequest.full(db.withIncremental(true)), out))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("binary logging (log_bin)");
    }

    @Test
    void incrementalBackupsCopyTheBinaryLogsSinceTheCheckpoint() throws IOException {
        runner.respond("").respond("binlog.000011\t180\tNo\nbinlog.000012\t9000\tNo\nbinlog.000013\t157\tNo\n")
                .respond("");
        Path out = tmp.resolve("incr.binlog.tar");

        DumpResult result = adapter.backupChanges(new ChangesRequest(db.withIncremental(true), BackupType.INCREMENTAL,
                Map.of("binlogFile", "binlog.000012", "binlogPosition", "4711"), null, "logical"), out);

        assertThat(runner.specs.get(0).command()).contains("FLUSH BINARY LOGS");
        assertThat(runner.specs.get(1).command()).contains("SHOW BINARY LOGS");
        List<String> copy = runner.lastCommand();
        assertThat(copy.get(0)).endsWith("mysqlbinlog");
        assertThat(copy).contains("--read-from-remote-server", "--raw").endsWith("binlog.000012");
        assertThat(result.checkpoint()).containsEntry("binlogFile", "binlog.000013").containsEntry("binlogPosition", "4");
        assertThat(out).exists();

        runner.respond("").respond("binlog.000013\t157\tNo\nbinlog.000014\t157\tNo\n");
        assertThatThrownBy(() -> adapter.backupChanges(new ChangesRequest(db, BackupType.INCREMENTAL,
                Map.of("binlogFile", "binlog.000012", "binlogPosition", "4"), null, "logical"), tmp.resolve("x")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("binlog.000012 is no longer available");
    }

    @Test
    void quotesOptionValuesAndIdentifiers() {
        assertThat(MySqlAdapter.optionValue("a\\b\nc")).isEqualTo("\"a\\\\b\\nc\"");
        assertThat(MySqlAdapter.quote("we`ird")).isEqualTo("`we``ird`");
    }
}
