/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Supported database management systems.
 */
public enum DatabaseType {
    MYSQL("mysql", 3306, "sql", List.of("mysql")),
    MARIADB("mariadb", 3306, "sql", List.of("mariadb")),
    POSTGRESQL("postgresql", 5432, "dump", List.of("postgresql", "postgres", "pg", "pgsql")),
    MONGODB("mongodb", 27017, "archive", List.of("mongodb", "mongo")),
    SQLITE("sqlite", 0, "db", List.of("sqlite", "sqlite3"));

    private final String id;
    private final int defaultPort;
    private final String fileExtension;
    private final List<String> aliases;

    DatabaseType(String id, int defaultPort, String fileExtension, List<String> aliases) {
        this.id = id;
        this.defaultPort = defaultPort;
        this.fileExtension = fileExtension;
        this.aliases = aliases;
    }

    @JsonValue
    public String id() {
        return id;
    }

    public int defaultPort() {
        return defaultPort;
    }

    /** Extension of the raw (uncompressed) dump produced for this database type. */
    public String fileExtension() {
        return fileExtension;
    }

    /** True for server-based databases, false for file-based ones such as SQLite. */
    public boolean isNetworked() {
        return this != SQLITE;
    }

    @JsonCreator
    public static DatabaseType fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("database type must not be empty");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DatabaseType type : values()) {
            if (type.aliases.contains(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unsupported database type '" + value + "' (supported: " + supported() + ")");
    }

    public static String supported() {
        return Arrays.stream(values()).map(DatabaseType::id).collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return id;
    }
}
