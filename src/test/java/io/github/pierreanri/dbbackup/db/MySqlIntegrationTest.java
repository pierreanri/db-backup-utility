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
 * {@code _PASSWORD}; set {@code DBBACKUP_IT_MYSQL_MARIADB=true} for MariaDB tools).
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
                .withDatabase(name);
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
