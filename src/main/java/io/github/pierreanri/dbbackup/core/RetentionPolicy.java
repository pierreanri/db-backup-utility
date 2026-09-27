package io.github.pierreanri.dbbackup.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.pierreanri.dbbackup.config.RetentionConfig;

/**
 * Decides which backups have expired. A backup expires when it is beyond the {@code keepLast}
 * most recent ones or older than {@code maxAgeDays}; the most recent backup never expires.
 */
public record RetentionPolicy(Integer keepLast, Integer maxAgeDays) {

    public static RetentionPolicy of(RetentionConfig config) {
        return config == null ? new RetentionPolicy(null, null)
                : new RetentionPolicy(config.keepLast(), config.maxAgeDays());
    }

    public boolean isEmpty() {
        return keepLast == null && maxAgeDays == null;
    }

    public List<BackupManifest> selectExpired(List<BackupManifest> backups, Instant now) {
        if (isEmpty()) {
            return List.of();
        }
        List<BackupManifest> sorted = new ArrayList<>(backups);
        sorted.sort(Comparator.comparing(BackupManifest::createdAt).reversed());
        Instant cutoff = maxAgeDays == null ? null : now.minus(Duration.ofDays(maxAgeDays));
        List<BackupManifest> expired = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            BackupManifest backup = sorted.get(i);
            boolean tooMany = keepLast != null && i >= keepLast;
            boolean tooOld = cutoff != null && backup.createdAt().isBefore(cutoff);
            if (tooMany || tooOld) {
                expired.add(backup);
            }
        }
        return expired;
    }
}
