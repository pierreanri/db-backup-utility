package io.github.pierreanri.dbbackup.db;

import java.nio.file.Path;

import io.github.pierreanri.dbbackup.config.DatabaseConfig;

/**
 * Engine specific backup and restore logic. Implementations are stateless: the connection
 * settings are passed with every call.
 */
public interface DatabaseAdapter {

    /**
     * Checks that the database is reachable with the configured credentials.
     *
     * @return a short description of the server, e.g. its version
     * @throws io.github.pierreanri.dbbackup.DbBackupException when the connection fails
     */
    String testConnection(DatabaseConfig database);

    /** Dumps the database into {@code outputFile} (uncompressed). */
    void backup(BackupRequest request, Path outputFile);

    /** Restores {@code dumpFile} (uncompressed) into the target database. */
    void restore(RestoreRequest request, Path dumpFile);

    /** Whether {@link RestoreRequest#tables()} can be used to restore only some tables/collections. */
    default boolean supportsSelectiveRestore() {
        return false;
    }
}
