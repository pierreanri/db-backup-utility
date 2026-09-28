package io.github.pierreanri.dbbackup.config;

/**
 * Logging settings.
 *
 * @param dir            directory of the rolling log file and of the activity history;
 *                       {@code ~/.dbbackup/logs} when unset
 * @param level          log level of the log file (TRACE, DEBUG, INFO, WARN, ERROR); INFO when unset
 * @param maxFileSize    size at which the log file rolls over, e.g. {@code 10MB}
 * @param maxHistoryDays number of days rolled log files are kept
 * @param file           set to {@code false} to disable the log file
 */
public record LoggingConfig(
        String dir,
        String level,
        String maxFileSize,
        Integer maxHistoryDays,
        Boolean file) {

    public static final LoggingConfig DEFAULT = new LoggingConfig(null, null, null, null, null);

    public boolean fileEnabled() {
        return file == null || file;
    }
}
