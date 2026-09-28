package io.github.pierreanri.dbbackup;

/**
 * Base class for all expected failures of the utility (bad configuration, failing dump tool,
 * unreachable storage...). The CLI prints the message of these exceptions without a stack trace.
 */
public class DbBackupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DbBackupException(String message) {
        super(message);
    }

    public DbBackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
