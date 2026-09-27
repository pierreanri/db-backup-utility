package io.github.pierreanri.dbbackup.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.util.PathUtils;

/**
 * SQLite support based on the online backup API of SQLite (through the xerial JDBC driver), which
 * produces a consistent copy even while the database is in use. No external tool is needed.
 *
 * <p>Backups are SQLite database files. Selective backups and schema-only backups copy the
 * selected table definitions (and data) into a new database file.
 */
public class SqliteAdapter implements DatabaseAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteAdapter.class);
    private static final int BUSY_TIMEOUT_MS = 30_000;

    @Override
    public String testConnection(DatabaseConfig database) {
        Path file = requireExistingFile(database.file());
        try (Connection conn = open(file, true);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT sqlite_version()")) {
            rs.next();
            String version = rs.getString(1);
            return "SQLite " + version + " (" + file + ", " + tables(conn, "main").size() + " tables)";
        } catch (SQLException e) {
            throw new DbBackupException("Cannot open SQLite database " + file + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void backup(BackupRequest request, Path outputFile) {
        Path source = requireExistingFile(request.database().file());
        try {
            Files.deleteIfExists(outputFile);
            if (request.scope() == BackupScope.FULL && request.tables().isEmpty()) {
                fullBackup(source, outputFile);
            } else if (request.scope() == BackupScope.DATA_ONLY) {
                throw new DbBackupException("SQLite backups do not support the data-only scope");
            } else {
                partialBackup(source, outputFile, request.tables(), request.scope() == BackupScope.FULL);
            }
            checkIntegrity(outputFile);
        } catch (SQLException | java.io.IOException e) {
            throw new DbBackupException("SQLite backup of " + source + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void restore(RestoreRequest request, Path dumpFile) {
        String target = request.targetDatabase() != null && !request.targetDatabase().isBlank()
                ? request.targetDatabase()
                : request.database().file();
        if (target == null || target.isBlank()) {
            throw new DbBackupException("No SQLite target file: set 'file' on the database or use --target-database");
        }
        Path targetFile = PathUtils.expand(target).toAbsolutePath();
        try {
            if (targetFile.getParent() != null) {
                Files.createDirectories(targetFile.getParent());
            }
            if (request.tables().isEmpty()) {
                try (Connection conn = open(targetFile, false)) {
                    LOG.info("Restoring SQLite database {} from {}", targetFile, dumpFile.getFileName());
                    int rc = conn.unwrap(SQLiteConnection.class).getDatabase()
                            .restore("main", dumpFile.toAbsolutePath().toString(), null);
                    if (rc != 0) {
                        throw new SQLException("SQLite restore returned code " + rc);
                    }
                }
            } else {
                selectiveRestore(dumpFile, targetFile, request.tables());
            }
        } catch (SQLException | java.io.IOException e) {
            throw new DbBackupException("SQLite restore into " + targetFile + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supportsSelectiveRestore() {
        return true;
    }

    private void fullBackup(Path source, Path outputFile) throws SQLException {
        try (Connection conn = open(source, true)) {
            LOG.info("Copying SQLite database {} with the online backup API", source);
            int rc = conn.unwrap(SQLiteConnection.class).getDatabase()
                    .backup("main", outputFile.toAbsolutePath().toString(), null);
            if (rc != 0) {
                throw new SQLException("SQLite backup returned code " + rc);
            }
        }
    }

    private void partialBackup(Path source, Path outputFile, List<String> requestedTables, boolean withData)
            throws SQLException {
        try (Connection conn = open(outputFile, false)) {
            attach(conn, source, "src");
            List<String> allTables = tables(conn, "src");
            List<String> selected = requestedTables.isEmpty() ? allTables : requestedTables;
            for (String table : selected) {
                if (!allTables.contains(table)) {
                    throw new DbBackupException("Table '" + table + "' does not exist in " + source
                            + " (available: " + String.join(", ", allTables) + ")");
                }
            }
            LOG.info("Copying {} of {} table(s) from {}{}", selected.size(), allTables.size(), source,
                    withData ? "" : " (schema only)");
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                for (String table : selected) {
                    copyTable(conn, stmt, "src", table, withData);
                }
                boolean everything = requestedTables.isEmpty();
                for (String sql : dependentObjects(conn, "src", everything ? null : selected)) {
                    stmt.execute(sql);
                }
            }
            conn.commit();
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DETACH DATABASE src");
            }
        }
    }

    private void selectiveRestore(Path dumpFile, Path targetFile, List<String> requestedTables) throws SQLException {
        try (Connection conn = open(targetFile, false)) {
            attach(conn, dumpFile, "src");
            List<String> available = tables(conn, "src");
            for (String table : requestedTables) {
                if (!available.contains(table)) {
                    throw new DbBackupException("Table '" + table + "' is not part of the backup (available: "
                            + String.join(", ", available) + ")");
                }
            }
            LOG.info("Restoring table(s) {} into {}", String.join(", ", requestedTables), targetFile);
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                for (String table : requestedTables) {
                    stmt.execute("DROP TABLE IF EXISTS main." + quote(table));
                    copyTable(conn, stmt, "src", table, true);
                }
                for (String sql : dependentObjects(conn, "src", requestedTables)) {
                    stmt.execute(sql);
                }
            }
            conn.commit();
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DETACH DATABASE src");
            }
        }
    }

    /** Creates {@code table} in the main schema from its definition in {@code schema}, optionally copying rows. */
    private static void copyTable(Connection conn, Statement stmt, String schema, String table, boolean withData)
            throws SQLException {
        String ddl;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sql FROM " + schema + ".sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("table " + table + " not found");
                }
                ddl = rs.getString(1);
            }
        }
        stmt.execute(ddl);
        if (withData) {
            stmt.execute("INSERT INTO main." + quote(table) + " SELECT * FROM " + schema + "." + quote(table));
        }
    }

    /** Index, trigger and view definitions of {@code schema}, restricted to {@code tables} when not null. */
    private static List<String> dependentObjects(Connection conn, String schema, List<String> tables)
            throws SQLException {
        List<String> statements = new ArrayList<>();
        String sql = "SELECT type, tbl_name, sql FROM " + schema + ".sqlite_master "
                + "WHERE type IN ('index', 'trigger', 'view') AND sql IS NOT NULL "
                + "ORDER BY CASE type WHEN 'index' THEN 0 WHEN 'view' THEN 1 ELSE 2 END";
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String type = rs.getString(1);
                String table = rs.getString(2);
                if (tables == null || (!"view".equals(type) && tables.contains(table))) {
                    statements.add(rs.getString(3));
                }
            }
        }
        return statements;
    }

    private static List<String> tables(Connection conn, String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT name FROM " + schema + ".sqlite_master "
                        + "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private static void attach(Connection conn, Path file, String alias) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("ATTACH DATABASE ? AS " + alias)) {
            ps.setString(1, file.toAbsolutePath().toString());
            ps.execute();
        }
    }

    private static void checkIntegrity(Path file) throws SQLException {
        try (Connection conn = open(file, true);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("PRAGMA quick_check")) {
            String result = rs.next() ? rs.getString(1) : "no result";
            if (!"ok".equalsIgnoreCase(result)) {
                throw new SQLException("integrity check of the backup failed: " + result);
            }
        }
    }

    private static Connection open(Path file, boolean readOnly) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(readOnly);
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
        return config.createConnection("jdbc:sqlite:" + file.toAbsolutePath());
    }

    private static Path requireExistingFile(String file) {
        if (file == null || file.isBlank()) {
            throw new DbBackupException("No SQLite database file configured");
        }
        Path path = PathUtils.expand(file).toAbsolutePath();
        if (!Files.isRegularFile(path)) {
            throw new DbBackupException("SQLite database file not found: " + path);
        }
        return path;
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
