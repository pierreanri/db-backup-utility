/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

/**
 * Returns the adapter handling a database type.
 */
public class DatabaseAdapters {

    private final ProcessRunner runner;

    public DatabaseAdapters() {
        this(new ProcessRunner());
    }

    public DatabaseAdapters(ProcessRunner runner) {
        this.runner = runner;
    }

    public DatabaseAdapter forType(DatabaseType type) {
        return switch (type) {
            case MYSQL -> new MySqlAdapter(runner, false);
            case MARIADB -> new MySqlAdapter(runner, true);
            case POSTGRESQL -> new PostgresAdapter(runner);
            case MONGODB -> new MongoAdapter(runner);
            case SQLITE -> new SqliteAdapter();
        };
    }
}
