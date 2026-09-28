package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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

import io.github.pierreanri.dbbackup.config.DatabaseConfig;

/**
 * Physical (pg_basebackup) full, incremental and differential backups against PostgreSQL 17+.
 * Enabled when {@code DBBACKUP_IT_PG17_HOST} is set (plus optional {@code _PORT}, {@code _USER},
 * {@code _PASSWORD} and {@code _BIN}, the directory of the PostgreSQL 17 programs). The server
 * needs {@code summarize_wal = on} and must accept replication connections. When the tests do not
 * run as root, the restored data directory is also started to check its content.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DBBACKUP_IT_PG17_HOST", matches = ".+")
class PostgresPhysicalIntegrationTest {

    @TempDir
    Path tmp;

    private final ProcessRunner runner = new ProcessRunner();
    private final PostgresAdapter adapter = new PostgresAdapter(runner);
    private final String bin = System.getenv("DBBACKUP_IT_PG17_BIN");
    private DatabaseConfig db;
    private String database;

    @BeforeEach
    void setUp() {
        database = "dbbackup_phys_" + UUID.randomUUID().toString().substring(0, 8);
        db = DatabaseConfig.of("it", DatabaseType.POSTGRESQL)
                .withHost(System.getenv("DBBACKUP_IT_PG17_HOST"))
                .withPort(Integer.valueOf(env("DBBACKUP_IT_PG17_PORT", "5432")))
                .withCredentials(env("DBBACKUP_IT_PG17_USER", "postgres"), System.getenv("DBBACKUP_IT_PG17_PASSWORD"))
                .withDatabase("postgres")
                .withBinPath(bin)
                .withIncremental(true);
        sql("postgres", "CREATE DATABASE " + database);
        sql(database, "CREATE TABLE t (id int PRIMARY KEY, v text);"
                + "INSERT INTO t SELECT g, 'v' || g FROM generate_series(1, 1000) g");
    }

    @AfterEach
    void tearDown() {
        sql("postgres", "DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void restoresPhysicalChains() throws Exception {
        Path full = tmp.resolve("full.base.tar");
        DumpResult fullResult = adapter.backup(BackupRequest.full(db), full);
        assertThat(fullResult.method()).isEqualTo("physical");
        assertThat(fullResult.checkpoint()).containsKey("startLsn");

        sql(database, "INSERT INTO t SELECT g, 'new' || g FROM generate_series(1001, 1500) g;"
                + "UPDATE t SET v = 'updated' WHERE id <= 10");
        Path incr1 = tmp.resolve("incr1.incr.tar");
        DumpResult first = adapter.backupChanges(new ChangesRequest(db, BackupType.INCREMENTAL,
                fullResult.checkpoint(), fullResult.stateFile(), "physical"), incr1);

        sql(database, "DELETE FROM t WHERE id > 1400");
        Path incr2 = tmp.resolve("incr2.incr.tar");
        adapter.backupChanges(new ChangesRequest(db, BackupType.INCREMENTAL, first.checkpoint(), first.stateFile(),
                "physical"), incr2);
        Path diff = tmp.resolve("diff.incr.tar");
        adapter.backupChanges(new ChangesRequest(db, BackupType.DIFFERENTIAL, fullResult.checkpoint(),
                fullResult.stateFile(), "physical"), diff);
        assertThat(Files.size(incr1)).isLessThan(Files.size(full));

        Path viaIncrementals = tmp.resolve("restored-incr");
        adapter.restoreChain(new RestoreRequest(db, null, null, List.of(), false, viaIncrementals), full,
                List.of(incr1, incr2));
        Path viaDifferential = tmp.resolve("restored-diff");
        adapter.restoreChain(new RestoreRequest(db, null, null, List.of(), false, viaDifferential), full,
                List.of(diff));

        if (!"root".equals(System.getProperty("user.name")) && bin != null) {
            assertThat(queryRestored(viaIncrementals, "SELECT count(*) || ',' || sum((v = 'updated')::int) FROM t"))
                    .isEqualTo("1400,10");
            assertThat(queryRestored(viaDifferential, "SELECT max(id) FROM t")).isEqualTo("1400");
        }
    }

    /** Starts a throwaway server on a restored data directory and runs a query in the test database. */
    private String queryRestored(Path dataDir, String query) throws IOException {
        if (!Files.exists(dataDir.resolve("postgresql.conf"))) {
            Files.writeString(dataDir.resolve("postgresql.conf"), "");
        }
        Files.writeString(dataDir.resolve("pg_hba.conf"), "local all all trust\n");
        Path socket = Files.createDirectories(tmp.resolve("socket-" + UUID.randomUUID().toString().substring(0, 6)));
        String port = String.valueOf(20000 + (int) (Math.random() * 20000));
        String pgCtl = Path.of(bin, "pg_ctl").toString();
        runner.run(ProcessSpec.builder(List.of(pgCtl, "-D", dataDir.toString(), "-w", "-l",
                tmp.resolve("server-" + port + ".log").toString(), "-o",
                "-p " + port + " -c listen_addresses='' -c unix_socket_directories=" + socket
                        + " -c hba_file=" + dataDir.resolve("pg_hba.conf") + " -c archive_mode=off", "start")).build());
        try {
            return runner.run(ProcessSpec.builder(List.of(Path.of(bin, "psql").toString(), "-h", socket.toString(),
                    "-p", port, "-U", db.username(), "-d", database, "-X", "-t", "-A", "-c", query)).build())
                    .stdout().strip();
        } finally {
            runner.run(ProcessSpec.builder(List.of(pgCtl, "-D", dataDir.toString(), "-m", "fast", "-w", "stop"))
                    .build());
        }
    }

    private void sql(String target, String statement) {
        List<String> command = new ArrayList<>(List.of(bin != null ? Path.of(bin, "psql").toString() : "psql",
                "--host=" + db.effectiveHost(), "--port=" + db.effectivePort(), "--username=" + db.username(),
                "--no-password", "--dbname=" + target, "-X", "-q", "-v", "ON_ERROR_STOP=1", "-c", statement));
        ProcessSpec.Builder spec = ProcessSpec.builder(command);
        if (db.hasPassword()) {
            spec.env("PGPASSWORD", db.password());
        }
        runner.run(spec.build());
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
