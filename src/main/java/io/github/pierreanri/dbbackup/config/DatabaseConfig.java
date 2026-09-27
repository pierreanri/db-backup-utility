package io.github.pierreanri.dbbackup.config;

import java.util.List;

import io.github.pierreanri.dbbackup.db.DatabaseType;
import io.github.pierreanri.dbbackup.util.Secrets;

/**
 * Connection settings of one database profile ({@code databases.<name>} in the config file).
 *
 * @param name           profile name (the key in the {@code databases} map)
 * @param type           database management system
 * @param host           server host name, defaults to {@code localhost}
 * @param port           server port, defaults to the standard port of {@code type}
 * @param username       user to connect as
 * @param password       password (use {@code ${ENV_VAR}} rather than plain text)
 * @param database       database (schema) name to back up
 * @param uri            MongoDB connection string, used instead of host/port/credentials
 * @param file           SQLite database file
 * @param authDatabase   MongoDB authentication database ({@code authSource})
 * @param binPath        directory containing the client tools (mysqldump, pg_dump, mongodump...)
 * @param dumpArgs       extra arguments appended to the dump command
 * @param restoreArgs    extra arguments appended to the restore command
 * @param timeoutMinutes maximum duration of a dump or restore; unlimited when not set
 * @param incremental    enable incremental/differential backups (full backups then record the
 *                       position the next incremental backup starts from; PostgreSQL switches to
 *                       physical backups)
 */
public record DatabaseConfig(
        String name,
        DatabaseType type,
        String host,
        Integer port,
        String username,
        String password,
        String database,
        String uri,
        String file,
        String authDatabase,
        String binPath,
        List<String> dumpArgs,
        List<String> restoreArgs,
        Integer timeoutMinutes,
        Boolean incremental) {

    public DatabaseConfig {
        dumpArgs = dumpArgs == null ? List.of() : List.copyOf(dumpArgs);
        restoreArgs = restoreArgs == null ? List.of() : List.copyOf(restoreArgs);
    }

    /** Minimal constructor, mostly useful for ad-hoc command line usage and tests. */
    public static DatabaseConfig of(String name, DatabaseType type) {
        return new DatabaseConfig(name, type, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    public DatabaseConfig withName(String newName) {
        return new DatabaseConfig(newName, type, host, port, username, password, database, uri, file, authDatabase,
                binPath, dumpArgs, restoreArgs, timeoutMinutes, incremental);
    }

    public DatabaseConfig withHost(String newHost) {
        return new DatabaseConfig(name, type, newHost, port, username, password, database, uri, file, authDatabase,
                binPath, dumpArgs, restoreArgs, timeoutMinutes, incremental);
    }

    public DatabaseConfig withPort(Integer newPort) {
        return new DatabaseConfig(name, type, host, newPort, username, password, database, uri, file, authDatabase,
                binPath, dumpArgs, restoreArgs, timeoutMinutes, incremental);
    }

    public DatabaseConfig withCredentials(String newUsername, String newPassword) {
        return new DatabaseConfig(name, type, host, port, newUsername, newPassword, database, uri, file, authDatabase,
                binPath, dumpArgs, restoreArgs, timeoutMinutes, incremental);
    }

    public DatabaseConfig withDatabase(String newDatabase) {
        return new DatabaseConfig(name, type, host, port, username, password, newDatabase, uri, file, authDatabase,
                binPath, dumpArgs, restoreArgs, timeoutMinutes, incremental);
    }

    public DatabaseConfig withUri(String newUri) {
        return new DatabaseConfig(name, type, host, port, username, password, database, newUri, file, authDatabase,
                binPath, dumpArgs, restoreArgs, timeoutMinutes, incremental);
    }

    public DatabaseConfig withFile(String newFile) {
        return new DatabaseConfig(name, type, host, port, username, password, database, uri, newFile, authDatabase,
                binPath, dumpArgs, restoreArgs, timeoutMinutes, incremental);
    }

    public DatabaseConfig withAuthDatabase(String newAuthDatabase) {
        return new DatabaseConfig(name, type, host, port, username, password, database, uri, file, newAuthDatabase,
                binPath, dumpArgs, restoreArgs, timeoutMinutes, incremental);
    }

    public DatabaseConfig withBinPath(String newBinPath) {
        return new DatabaseConfig(name, type, host, port, username, password, database, uri, file, authDatabase,
                newBinPath, dumpArgs, restoreArgs, timeoutMinutes, incremental);
    }

    public DatabaseConfig withTimeoutMinutes(Integer newTimeoutMinutes) {
        return new DatabaseConfig(name, type, host, port, username, password, database, uri, file, authDatabase,
                binPath, dumpArgs, restoreArgs, newTimeoutMinutes, incremental);
    }

    public DatabaseConfig withIncremental(Boolean newIncremental) {
        return new DatabaseConfig(name, type, host, port, username, password, database, uri, file, authDatabase,
                binPath, dumpArgs, restoreArgs, timeoutMinutes, newIncremental);
    }

    public boolean isIncremental() {
        return incremental != null && incremental;
    }

    public String effectiveHost() {
        return host == null || host.isBlank() ? "localhost" : host;
    }

    public int effectivePort() {
        return port == null ? type.defaultPort() : port;
    }

    public boolean hasPassword() {
        return password != null && !password.isEmpty();
    }

    /** Human readable description that never contains the password. */
    public String describe() {
        if (type == null) {
            return String.valueOf(name);
        }
        if (type == DatabaseType.SQLITE) {
            return "sqlite:" + file;
        }
        if (uri != null && !uri.isBlank()) {
            return Secrets.maskUri(uri) + (database != null ? " (db " + database + ")" : "");
        }
        String user = username != null && !username.isBlank() ? username + "@" : "";
        String db = database != null ? "/" + database : "";
        return type.id() + "://" + user + effectiveHost() + ":" + effectivePort() + db;
    }
}
