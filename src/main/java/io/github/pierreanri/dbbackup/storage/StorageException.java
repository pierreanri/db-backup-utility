package io.github.pierreanri.dbbackup.storage;

import io.github.pierreanri.dbbackup.DbBackupException;

/**
 * Raised when a storage backend operation fails.
 */
public class StorageException extends DbBackupException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
