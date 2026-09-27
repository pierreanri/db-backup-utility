/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Runs pg_dump/pg_restore against a real server. Enabled when {@code DBBACKUP_IT_PG_HOST} is set
 * (plus optional {@code _PORT}, {@code _USER}, {@code _PASSWORD}).
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DBBACKUP_IT_PG_HOST", matches = ".+")
class PostgresIntegrationTest {

    @TempDir
    Path tmp;

    private final ProcessRunner runner = new ProcessRunner();
    private final PostgresAdapter adapter = new PostgresAdapter(runner);
    private final List<String> createdDatabases = new ArrayList<>();
    private DatabaseConfig db;

    @BeforeEach
    void createDatabase() {
        String name = "dbbackup_it_" + UUID.randomUUID().toString().substring(0, 8);
        db = DatabaseConfig.of("it", DatabaseType.POSTGRESQL)
                .withHost(System.getenv("DBBACKUP_IT_PG_HOST"))
                .withPort(Integer.valueOf(env("DBBACKUP_IT_PG_PORT", "5432")))
                .withCredentials(env("DBBACKUP_IT_PG_USER", "postgres"), System.getenv("DBBACKUP_IT_PG_PASSWORD"))
                .withDatabase(name);
        createdDatabases.add(name);
        sql("postgres", "CREATE DATABASE " + name);
        sql(name, """
                CREATE TABLE customers (id serial PRIMARY KEY, name text NOT NULL);
                CREATE TABLE orders (id serial PRIMARY KEY, customer_id int REFERENCES customers(id), total numeric);
                CREATE INDEX orders_customer ON orders(customer_id);
                INSERT INTO customers(name) VALUES ('ada'), ('grace'), ('linus');
                INSERT INTO orders(customer_id, total) VALUES (1, 10.5), (2, 99), (2, 1);
                """);
    }

    @AfterEach
    void dropDatabases() {
        for (String name : createdDatabases) {
            try {
                sql("postgres", "DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            } catch (RuntimeException ignored) {
                // best effort cleanup
            }
        }
    }

    @Test
    void testsConnection() {
        assertThat(adapter.testConnection(db)).startsWith("PostgreSQL ");
    }

    @Test
    void backsUpAndRestoresIntoNewDatabase() {
        Path dump = tmp.resolve("db.dump");
        adapter.backup(BackupRequest.full(db), dump);
        assertThat(PostgresAdapter.isCustomFormat(dump)).isTrue();

        String restored = db.database() + "_restored";
        createdDatabases.add(restored);
        adapter.restore(new RestoreRequest(db, restored, db.database(), List.of(), false), dump);

        assertThat(sql(restored, "SELECT count(*) FROM customers")).isEqualTo("3");
        assertThat(sql(restored, "SELECT sum(total) FROM orders")).isEqualTo("110.5");
    }

    @Test
    void cleanRestoreOverwritesExistingData() {
        Path dump = tmp.resolve("db.dump");
        adapter.backup(BackupRequest.full(db), dump);

        sql(db.database(), "DELETE FROM orders; DELETE FROM customers WHERE name = 'ada'");
        adapter.restore(new RestoreRequest(db, null, db.database(), List.of(), true), dump);

        assertThat(sql(db.database(), "SELECT count(*) FROM customers")).isEqualTo("3");
        assertThat(sql(db.database(), "SELECT count(*) FROM orders")).isEqualTo("3");
    }

    @Test
    void restoresSelectedTables() {
        Path dump = tmp.resolve("db.dump");
        adapter.backup(BackupRequest.full(db), dump);

        String partial = db.database() + "_partial";
        createdDatabases.add(partial);
        adapter.restore(new RestoreRequest(db, partial, db.database(), List.of("customers"), false), dump);

        assertThat(sql(partial, "SELECT string_agg(tablename, ',' ORDER BY tablename) FROM pg_tables "
                + "WHERE schemaname = 'public'")).isEqualTo("customers");
        assertThat(sql(partial, "SELECT count(*) FROM customers")).isEqualTo("3");
    }

    @Test
    void schemaOnlyBackupHasNoRows() {
        Path dump = tmp.resolve("schema.dump");
        adapter.backup(new BackupRequest(db, BackupScope.SCHEMA_ONLY, List.of()), dump);

        String restored = db.database() + "_schema";
        createdDatabases.add(restored);
        adapter.restore(new RestoreRequest(db, restored, db.database(), List.of(), false), dump);

        assertThat(sql(restored, "SELECT count(*) FROM customers")).isEqualTo("0");
    }

    private String sql(String database, String statement) {
        List<String> command = new ArrayList<>(List.of("psql", "--host=" + db.effectiveHost(),
                "--port=" + db.effectivePort(), "--username=" + db.username(), "--no-password",
                "--dbname=" + database, "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1", "-c", statement));
        ProcessSpec.Builder spec = ProcessSpec.builder(command);
        if (db.hasPassword()) {
            spec.env("PGPASSWORD", db.password());
        }
        return runner.run(spec.build()).stdout().strip();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
