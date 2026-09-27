/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.config;

import java.util.List;

/**
 * Defaults applied when a command or schedule does not specify a value.
 *
 * @param compression    compression algorithm (gzip, bzip2, xz or none); gzip when unset
 * @param storage        storage targets used when none is given
 * @param workDir        directory for temporary dump files; the system temp directory when unset
 * @param retention      default retention rules
 * @param timeoutMinutes default maximum duration of dump/restore commands
 */
public record DefaultsConfig(
        String compression,
        List<String> storage,
        String workDir,
        RetentionConfig retention,
        Integer timeoutMinutes) {

    public static final DefaultsConfig EMPTY = new DefaultsConfig(null, null, null, null, null);

    public DefaultsConfig {
        storage = storage == null ? List.of() : List.copyOf(storage);
    }
}
