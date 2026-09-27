/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.util;

import java.nio.file.Path;

/**
 * Helpers for user supplied paths.
 */
public final class PathUtils {

    private PathUtils() {
    }

    /** Directory holding the default config, logs and backups: {@code ~/.dbbackup}. */
    public static Path appHome() {
        return Path.of(System.getProperty("user.home"), ".dbbackup");
    }

    /** Expands a leading {@code ~} to the user's home directory. Returns {@code null} for {@code null}. */
    public static Path expand(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        if (trimmed.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home"), trimmed.substring(2));
        }
        return Path.of(trimmed);
    }
}
