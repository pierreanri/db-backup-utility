/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
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
