package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;

class PostgresAdapterTest {

    @TempDir
    Path tmp;

    private final RecordingRunner runner = new RecordingRunner();
    private final PostgresAdapter adapter = new PostgresAdapter(runner);
    private final DatabaseConfig db = DatabaseConfig.of("app", DatabaseType.POSTGRESQL)
            .withHost("pg.internal")
            .withPort(5433)
            .withCredentials("backup", "t0p-secret")
            .withDatabase("app");

    @Test
    void dumpsInCustomFormatWithPasswordInEnvironment() {
        Path out = tmp.resolve("app.dump");
        adapter.backup(new BackupRequest(db, BackupScope.SCHEMA_ONLY, List.of("public.users")), out);

        ProcessSpec spec = runner.last();
        assertThat(spec.command().get(0)).endsWith("pg_dump");
        assertThat(spec.command()).contains("--host=pg.internal", "--port=5433", "--username=backup", "--no-password",
                "--dbname=app", "--format=custom", "--file=" + out.toAbsolutePath(), "--schema-only",
                "--table=public.users");
        assertThat(String.join(" ", spec.command())).doesNotContain("t0p-secret");
        assertThat(spec.environment()).containsEntry("PGPASSWORD", "t0p-secret");
    }

    @Test
    void restoresCustomFormatWithPgRestore() throws IOException {
        Path dump = Files.write(tmp.resolve("app.dump"), "PGDMP\u0001rest".getBytes());
        runner.respond("1\n");
        adapter.restore(new RestoreRequest(db, "app_copy", "app", List.of("users"), true), dump);

        assertThat(runner.specs.get(0).command()).contains("--dbname=postgres")
                .contains("SELECT 1 FROM pg_database WHERE datname = 'app_copy'");
        List<String> cmd = runner.lastCommand();
        assertThat(cmd.get(0)).endsWith("pg_restore");
        assertThat(cmd).contains("--dbname=app_copy", "--clean", "--if-exists", "--table=users");
        assertThat(cmd.get(cmd.size() - 1)).isEqualTo(dump.toAbsolutePath().toString());
    }

    @Test
    void createsMissingTargetDatabase() throws IOException {
        Path dump = Files.write(tmp.resolve("app.dump"), "PGDMP".getBytes());
        runner.respond("");
        adapter.restore(new RestoreRequest(db, "new\"db", "app", List.of(), false), dump);

        assertThat(runner.specs).hasSize(3);
        assertThat(runner.specs.get(1).command()).contains("CREATE DATABASE \"new\"\"db\"");
    }

    @Test
    void restoresPlainSqlWithPsql() throws IOException {
        Path dump = Files.writeString(tmp.resolve("app.sql"), "CREATE TABLE x (id int);\n");
        runner.respond("1\n");
        adapter.restore(RestoreRequest.full(db), dump);

        List<String> cmd = runner.lastCommand();
        assertThat(cmd.get(0)).endsWith("psql");
        assertThat(cmd).contains("ON_ERROR_STOP=1", "-f", dump.toAbsolutePath().toString());

        assertThatThrownBy(() -> adapter.restore(new RestoreRequest(db, null, "app", List.of("x"), false), dump))
                .isInstanceOf(DbBackupException.class);
    }

    @Test
    void reportsServerVersion() {
        runner.respond("PostgreSQL 16.4 on x86_64-pc-linux-gnu, compiled by gcc\n");
        assertThat(adapter.testConnection(db)).isEqualTo("PostgreSQL 16.4");
    }
}
