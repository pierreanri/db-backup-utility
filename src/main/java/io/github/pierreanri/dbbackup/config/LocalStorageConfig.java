package io.github.pierreanri.dbbackup.config;

/**
 * Stores backups in a directory of the local file system (or a mounted network share).
 *
 * @param path      root directory of the backups
 * @param retention optional retention override for this target
 */
public record LocalStorageConfig(String path, RetentionConfig retention) implements StorageConfig {

    @Override
    public String typeId() {
        return "local";
    }
}
