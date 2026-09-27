/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.logging;

/**
 * Amount of log output printed on the console.
 */
public enum Verbosity {
    /** Only warnings and errors. */
    QUIET,
    /** Progress information. */
    NORMAL,
    /** Debug output including third-party libraries. */
    VERBOSE
}
