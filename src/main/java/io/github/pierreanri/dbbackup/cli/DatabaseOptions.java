/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.cli;

import java.nio.file.Path;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.AppConfig;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.core.BackupNaming;
import io.github.pierreanri.dbbackup.db.DatabaseType;
import picocli.CommandLine.Option;

/**
 * Connection options usable without a configuration file, or to override a profile.
 */
class DatabaseOptions {

    @Option(names = "--db-type", paramLabel = "TYPE",
            description = "Database type for ad-hoc use: mysql, mariadb, postgresql, mongodb, sqlite.")
    DatabaseType type;

    @Option(names = "--db-host", paramLabel = "HOST", description = "Server host (default: localhost).")
    String host;

    @Option(names = "--db-port", paramLabel = "PORT", description = "Server port (default: the standard port).")
    Integer port;

    @Option(names = "--db-user", paramLabel = "USER", description = "User name.")
    String user;

    @Option(names = "--db-password", paramLabel = "PASSWORD", arity = "0..1", interactive = true,
            description = "Password. Prompts for it when no value is given. Prefer --db-password-env.")
    String password;

    @Option(names = "--db-password-env", paramLabel = "VAR", description = "Read the password from this environment variable.")
    String passwordEnv;

    @Option(names = "--db-name", paramLabel = "NAME", description = "Database name.")
    String database;

    @Option(names = "--db-uri", paramLabel = "URI", description = "MongoDB connection string.")
    String uri;

    @Option(names = "--db-file", paramLabel = "FILE", description = "SQLite database file.")
    Path file;

    @Option(names = "--db-bin-path", paramLabel = "DIR", description = "Directory of the client tools (pg_dump, mysqldump...).")
    String binPath;

    boolean isAdHoc() {
        return type != null;
    }

    boolean hasOverrides() {
        return type != null || host != null || port != null || user != null || password != null || passwordEnv != null
                || database != null || uri != null || file != null || binPath != null;
    }

    /**
     * Returns the profile {@code name} with the command line overrides applied, or an ad-hoc
     * database when {@code name} is {@code null} and {@code --db-type} is set.
     */
    DatabaseConfig resolve(AppConfig config, String name) {
        DatabaseConfig db;
        if (name != null) {
            db = config.database(name);
            if (type != null && type != db.type()) {
                throw new DbBackupException("--db-type " + type + " does not match the type of '" + name + "' (" + db.type() + ")");
            }
        } else if (type != null) {
            String label = database != null ? database
                    : file != null ? stripExtension(file.getFileName().toString())
                    : type.id();
            db = DatabaseConfig.of(BackupNaming.sanitize(label), type);
        } else {
            throw new DbBackupException("No database given: name a configured database"
                    + (config.databases().isEmpty() ? "" : " (" + String.join(", ", config.databases().keySet()) + ")")
                    + " or use --db-type with connection options");
        }
        if (host != null) {
            db = db.withHost(host);
        }
        if (port != null) {
            db = db.withPort(port);
        }
        if (user != null || password != null || passwordEnv != null) {
            db = db.withCredentials(user != null ? user : db.username(), resolvePassword(db.password()));
        }
        if (database != null) {
            db = db.withDatabase(database);
        }
        if (uri != null) {
            db = db.withUri(uri);
        }
        if (file != null) {
            db = db.withFile(file.toString());
        }
        if (binPath != null) {
            db = db.withBinPath(binPath);
        }
        return db;
    }

    private String resolvePassword(String current) {
        if (password != null) {
            return password;
        }
        if (passwordEnv != null) {
            String value = System.getenv(passwordEnv);
            if (value == null) {
                throw new DbBackupException("Environment variable " + passwordEnv + " is not set");
            }
            return value;
        }
        return current;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
