package io.github.pierreanri.dbbackup.db;

import java.util.List;

import io.github.pierreanri.dbbackup.config.DatabaseConfig;

/**
 * Parameters of a restore.
 *
 * @param database       connection settings of the server to restore into
 * @param targetDatabase database name (SQLite: file) to restore into; {@code database.database()}
 *                       (SQLite: {@code database.file()}) when {@code null}
 * @param sourceDatabase name of the database the backup was taken from (used by MongoDB to
 *                       rename namespaces); may be {@code null}
 * @param tables         tables/collections to restore; everything when empty
 * @param clean          drop existing objects before restoring them
 */
public record RestoreRequest(
        DatabaseConfig database,
        String targetDatabase,
        String sourceDatabase,
        List<String> tables,
        boolean clean) {

    public RestoreRequest {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    public static RestoreRequest full(DatabaseConfig database) {
        return new RestoreRequest(database, null, database.database(), List.of(), false);
    }

    /** Database name to restore into. */
    public String effectiveTargetDatabase() {
        return targetDatabase != null && !targetDatabase.isBlank() ? targetDatabase : database.database();
    }
}
