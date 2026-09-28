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
