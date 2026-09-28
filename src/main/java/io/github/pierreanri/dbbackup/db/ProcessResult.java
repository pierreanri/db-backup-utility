package io.github.pierreanri.dbbackup.db;

/**
 * Outcome of an external command.
 *
 * @param exitCode process exit code
 * @param stdout   captured standard output (empty when redirected to a file)
 * @param stderr   last part of the standard error output
 */
public record ProcessResult(int exitCode, String stdout, String stderr) {
}
