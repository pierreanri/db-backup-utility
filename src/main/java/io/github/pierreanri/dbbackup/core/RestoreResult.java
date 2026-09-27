package io.github.pierreanri.dbbackup.core;

/**
 * Outcome of a restore.
 *
 * @param manifest       manifest of the restored backup, {@code null} for a file without manifest
 * @param source         location the backup was read from
 * @param durationMillis total duration
 */
public record RestoreResult(BackupManifest manifest, String source, long durationMillis) {
}
