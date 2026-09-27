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
            case SQLITE -> new SqliteAdapter();
            default -> throw new UnsupportedOperationException("No adapter for " + type + " yet");
        };
    }

    protected ProcessRunner runner() {
        return runner;
    }
}
