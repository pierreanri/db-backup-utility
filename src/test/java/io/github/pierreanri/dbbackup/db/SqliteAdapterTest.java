package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;

class SqliteAdapterTest {

    @TempDir
    Path tmp;

    private final SqliteAdapter adapter = new SqliteAdapter();
    private Path source;
    private DatabaseConfig config;

    @BeforeEach
    void createDatabase() throws SQLException {
        source = tmp.resolve("app.db");
        execute(source,
                "CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)",
                "CREATE INDEX users_name ON users(name)",
                "CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, total REAL)",
                "CREATE VIEW big_orders AS SELECT * FROM orders WHERE total > 100",
                "INSERT INTO users(name) VALUES ('ada'), ('grace'), ('linus')",
                "INSERT INTO orders VALUES (1, 1, 99.5), (2, 2, 250.0)");
        config = DatabaseConfig.of("app", io.github.pierreanri.dbbackup.db.DatabaseType.SQLITE).withFile(source.toString());
    }

    @Test
    void testsConnection() {
        assertThat(adapter.testConnection(config)).startsWith("SQLite 3.").contains("2 tables");
        assertThatThrownBy(() -> adapter.testConnection(config.withFile(tmp.resolve("nope.db").toString())))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("SQLite database file not found");
    }

    @Test
    void fullBackupAndRestore() throws SQLException {
        Path backup = tmp.resolve("backup.db");
        adapter.backup(BackupRequest.full(config), backup);
        assertThat(count(backup, "users")).isEqualTo(3);
        assertThat(count(backup, "big_orders")).isEqualTo(1);

        execute(source, "DELETE FROM users", "DROP TABLE orders");
        adapter.restore(RestoreRequest.full(config), backup);

        assertThat(count(source, "users")).isEqualTo(3);
        assertThat(count(source, "orders")).isEqualTo(2);
    }

    @Test
    void restoresIntoAnotherFile() throws SQLException {
        Path backup = tmp.resolve("backup.db");
        adapter.backup(BackupRequest.full(config), backup);

        Path copy = tmp.resolve("restored/copy.db");
        adapter.restore(new RestoreRequest(config, copy.toString(), null, List.of(), false), backup);

        assertThat(count(copy, "orders")).isEqualTo(2);
    }

    @Test
    void selectiveBackupCopiesOnlyRequestedTables() throws SQLException {
        Path backup = tmp.resolve("users.db");
        adapter.backup(new BackupRequest(config, BackupScope.FULL, List.of("users")), backup);

        assertThat(objects(backup)).containsExactlyInAnyOrder("users", "users_name", "sqlite_sequence");
        assertThat(count(backup, "users")).isEqualTo(3);
    }

    @Test
    void schemaOnlyBackupHasNoRows() throws SQLException {
        Path backup = tmp.resolve("schema.db");
        adapter.backup(new BackupRequest(config, BackupScope.SCHEMA_ONLY, List.of()), backup);

        assertThat(objects(backup)).contains("users", "orders", "users_name", "big_orders");
        assertThat(count(backup, "users")).isZero();
    }

    @Test
    void rejectsUnknownTablesAndDataOnly() {
        assertThatThrownBy(() -> adapter.backup(new BackupRequest(config, BackupScope.FULL, List.of("nope")),
                tmp.resolve("x.db")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("Table 'nope' does not exist");
        assertThatThrownBy(() -> adapter.backup(new BackupRequest(config, BackupScope.DATA_ONLY, List.of()),
                tmp.resolve("y.db")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("data-only");
    }

    @Test
    void selectiveRestoreReplacesOnlyRequestedTables() throws SQLException {
        Path backup = tmp.resolve("backup.db");
        adapter.backup(BackupRequest.full(config), backup);

        execute(source, "DELETE FROM users WHERE name = 'ada'", "INSERT INTO orders VALUES (3, 3, 5.0)");
        adapter.restore(new RestoreRequest(config, null, null, List.of("users"), false), backup);

        assertThat(count(source, "users")).isEqualTo(3);
        assertThat(count(source, "orders")).isEqualTo(3);
        assertThat(objects(source)).contains("users_name", "big_orders");
    }

    private static void execute(Path db, String... statements) throws SQLException {
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

    private static List<String> objects(Path db) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }
}
