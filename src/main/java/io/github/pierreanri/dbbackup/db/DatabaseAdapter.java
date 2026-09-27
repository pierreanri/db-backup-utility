package io.github.pierreanri.dbbackup.db;

import java.nio.file.Path;
import java.util.List;

import io.github.pierreanri.dbbackup.DbBackupException;
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

    /**
     * Dumps the database into {@code outputFile} (uncompressed). When incremental backups are
     * enabled for the database, the result carries the checkpoint the next incremental backup
     * starts from.
     */
    DumpResult backup(BackupRequest request, Path outputFile);

    /** Restores {@code dumpFile} (uncompressed) into the target database. */
    void restore(RestoreRequest request, Path dumpFile);

    /** Whether {@link RestoreRequest#tables()} can be used to restore only some tables/collections. */
    default boolean supportsSelectiveRestore() {
        return false;
    }

    /** Whether incremental and differential backups are supported. */
    default boolean supportsIncremental() {
        return false;
    }

    /** Extension of the raw dump file (without compression suffix). */
    default String fileExtension(DatabaseConfig database, BackupType type) {
        return database.type().fileExtension();
    }

    /** Writes the changes since {@code request.fromCheckpoint()} into {@code outputFile}. */
    default DumpResult backupChanges(ChangesRequest request, Path outputFile) {
        throw new DbBackupException(request.database().type() + " does not support incremental backups");
    }

    /**
     * Restores a full backup followed by incremental/differential changes, in order.
     *
     * @param fullDump the (uncompressed) full backup
     * @param changes  the (uncompressed) change files, oldest first; may be empty
     */
    default void restoreChain(RestoreRequest request, Path fullDump, List<Path> changes) {
        if (!changes.isEmpty()) {
            throw new DbBackupException(request.database().type() + " does not support incremental backups");
        }
        restore(request, fullDump);
    }
}
