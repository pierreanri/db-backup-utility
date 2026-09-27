/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
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
