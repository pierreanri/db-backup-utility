package io.github.pierreanri.dbbackup.config;

import io.github.pierreanri.dbbackup.DbBackupException;

/**
 * Raised when the configuration file cannot be read, parsed or validated.
 */
public class ConfigException extends DbBackupException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
