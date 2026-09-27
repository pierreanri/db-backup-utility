package io.github.pierreanri.dbbackup.core;

import java.util.List;

/**
 * Outcome of a backup.
 *
 * @param manifest       the backup's manifest
 * @param targets        per storage target outcome
 * @param durationMillis total duration including uploads and retention
 */
public record BackupResult(BackupManifest manifest, List<TargetResult> targets, long durationMillis) {

    public BackupResult {
        targets = List.copyOf(targets);
    }

    public boolean success() {
        return targets.stream().allMatch(TargetResult::success);
    }

    public boolean partial() {
        return !success() && targets.stream().anyMatch(TargetResult::success);
    }

    /**
     * @param storage  storage target name
     * @param location full location of the stored backup
     * @param success  whether the upload succeeded
     * @param error    error message when it did not
     * @param pruned   ids of expired backups deleted by the retention policy
     */
    public record TargetResult(String storage, String location, boolean success, String error, List<String> pruned) {

        public TargetResult {
            pruned = pruned == null ? List.of() : List.copyOf(pruned);
        }
    }
}
