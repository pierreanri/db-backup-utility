package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

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
    void quotesOptionValuesAndIdentifiers() {
        assertThat(MySqlAdapter.optionValue("a\\b\nc")).isEqualTo("\"a\\\\b\\nc\"");
        assertThat(MySqlAdapter.quote("we`ird")).isEqualTo("`we``ird`");
    }
}
