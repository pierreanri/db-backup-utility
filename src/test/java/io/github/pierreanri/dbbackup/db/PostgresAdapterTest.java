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

    /** Simulates pg_basebackup writing a data directory. */
    private void simulateBaseBackup() {
        runner.onRun = spec -> spec.command().stream().filter(arg -> arg.startsWith("--pgdata=")).findFirst()
                .ifPresent(arg -> {
                    try {
                        Path dir = Files.createDirectories(Path.of(arg.substring("--pgdata=".length())));
                        Files.writeString(dir.resolve("backup_manifest"), "{\"PostgreSQL-Backup-Manifest-Version\": 2}");
                        Files.writeString(dir.resolve("backup_label"),
                                "START WAL LOCATION: 0/2000028 (file 000000010000000000000002)\nSTART TIMELINE: 1\n");
                        Files.writeString(dir.resolve("PG_VERSION"), "17\n");
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
    }

    @Test
    void incrementalDatabasesTakePhysicalBackups() throws IOException {
        simulateBaseBackup();
        DatabaseConfig physical = db.withIncremental(true);
        Path full = tmp.resolve("full.base.tar");

        DumpResult result = adapter.backup(BackupRequest.full(physical), full);

        List<String> cmd = runner.lastCommand();
        assertThat(cmd.get(0)).endsWith("pg_basebackup");
        assertThat(cmd).contains("--format=plain", "--wal-method=stream", "--checkpoint=fast", "--no-password")
                .noneMatch(arg -> arg.startsWith("--incremental") || arg.startsWith("--dbname"));
        assertThat(runner.last().environment()).containsEntry("PGPASSWORD", "t0p-secret");
        assertThat(result.method()).isEqualTo("physical");
        assertThat(result.checkpoint()).containsEntry("startLsn", "0/2000028").containsEntry("timeline", "1");
        assertThat(result.stateFile()).hasContent("{\"PostgreSQL-Backup-Manifest-Version\": 2}");
        assertThat(full).exists();
        assertThat(adapter.fileExtension(physical, BackupType.INCREMENTAL)).isEqualTo("incr.tar");

        DumpResult incremental = adapter.backupChanges(new ChangesRequest(physical, BackupType.INCREMENTAL,
                result.checkpoint(), result.stateFile(), "physical"), tmp.resolve("incr.tar"));
        assertThat(runner.lastCommand()).contains("--incremental=" + result.stateFile().toAbsolutePath());
        assertThat(incremental.method()).isEqualTo("physical");

        assertThatThrownBy(() -> adapter.backup(new BackupRequest(physical, BackupScope.FULL, List.of("users")),
                tmp.resolve("x")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("physical copies of the whole server");
    }

    @Test
    void physicalChainsAreCombinedIntoATargetDirectory() throws IOException {
        simulateBaseBackup();
        DatabaseConfig physical = db.withIncremental(true);
        Path full = tmp.resolve("full.base.tar");
        DumpResult first = adapter.backup(BackupRequest.full(physical), full);
        Path incr = tmp.resolve("incr.tar");
        adapter.backupChanges(new ChangesRequest(physical, BackupType.INCREMENTAL, first.checkpoint(),
                first.stateFile(), "physical"), incr);
        runner.specs.clear();

        Path target = tmp.resolve("restored");
        adapter.restoreChain(new RestoreRequest(physical, null, null, List.of(), false, target), full, List.of(incr));

        assertThat(runner.specs.get(0).command().get(0)).endsWith("pg_combinebackup");
        assertThat(runner.specs.get(0).command()).contains("--output=" + target.toAbsolutePath());
        assertThat(runner.specs.get(0).command()).hasSize(4);
        assertThat(runner.lastCommand().get(0)).endsWith("pg_verifybackup");

        Path fullOnly = tmp.resolve("full-only");
        adapter.restoreChain(new RestoreRequest(physical, null, null, List.of(), false, fullOnly), full, List.of());
        assertThat(fullOnly.resolve("PG_VERSION")).hasContent("17\n");

        assertThatThrownBy(() -> adapter.restoreChain(new RestoreRequest(physical, null, null, List.of(), false,
                fullOnly), full, List.of()))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("is not empty");
        assertThatThrownBy(() -> adapter.restoreChain(RestoreRequest.full(db), full, List.of(incr)))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("--target-dir");
    }

    @Test
    void toleratesOnlyTheTransactionTimeoutErrorOfNewerClients() {
        String timeout = "pg_restore: error: could not execute query: ERROR:  unrecognized configuration parameter "
                + "\"transaction_timeout\"\nCommand was: SET transaction_timeout = 0;\n";
        assertThat(PostgresAdapter.onlyHarmlessErrors(timeout + "pg_restore: warning: errors ignored on restore: 1\n"))
                .isTrue();
        assertThat(PostgresAdapter.onlyHarmlessErrors(timeout
                + "pg_restore: error: could not execute query: ERROR:  relation \"users\" already exists\n"))
                .isFalse();
        assertThat(PostgresAdapter.onlyHarmlessErrors("pg_restore: error: connection failed")).isFalse();
        assertThat(PostgresAdapter.onlyHarmlessErrors("")).isFalse();
    }

    @Test
    void reportsServerVersion() {
        runner.respond("PostgreSQL 16.4 on x86_64-pc-linux-gnu, compiled by gcc\n");
        assertThat(adapter.testConnection(db)).isEqualTo("PostgreSQL 16.4");
    }
}
