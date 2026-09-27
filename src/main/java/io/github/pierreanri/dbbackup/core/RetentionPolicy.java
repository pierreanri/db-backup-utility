/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.pierreanri.dbbackup.config.RetentionConfig;

/**
 * Decides which backups have expired. A backup chain (a full backup and the incremental and
 * differential backups based on it) expires when it is beyond the {@code keepLast} most recent
 * chains or when its most recent backup is older than {@code maxAgeDays}; the most recent chain
 * never expires. Without incremental backups, every chain is a single full backup.
 */
public record RetentionPolicy(Integer keepLast, Integer maxAgeDays) {

    public static RetentionPolicy of(RetentionConfig config) {
        return config == null ? new RetentionPolicy(null, null)
                : new RetentionPolicy(config.keepLast(), config.maxAgeDays());
    }

    public boolean isEmpty() {
        return keepLast == null && maxAgeDays == null;
    }

    /**
     * Selects the expired backups. Incremental and differential backups belong to the chain of
     * their full backup: {@code keepLast} counts chains (full backups), a chain expires as a whole
     * when its most recent backup is older than {@code maxAgeDays}, and the most recent chain is
     * never deleted. Within an expired chain, dependent backups come before the full backup.
     */
    public List<BackupManifest> selectExpired(List<BackupManifest> backups, Instant now) {
        if (isEmpty()) {
            return List.of();
        }
        Map<String, List<BackupManifest>> chains = new LinkedHashMap<>();
        for (BackupManifest backup : backups) {
            String chainId = backup.chainId() != null ? backup.chainId() : backup.id();
            chains.computeIfAbsent(chainId, k -> new ArrayList<>()).add(backup);
        }
        List<List<BackupManifest>> ordered = new ArrayList<>(chains.values());
        ordered.forEach(chain -> chain.sort(Comparator.comparing(BackupManifest::createdAt).reversed()));
        ordered.sort(Comparator.comparing((List<BackupManifest> chain) -> chainStart(chain)).reversed());

        Instant cutoff = maxAgeDays == null ? null : now.minus(Duration.ofDays(maxAgeDays));
        List<BackupManifest> expired = new ArrayList<>();
        for (int i = 1; i < ordered.size(); i++) {
            List<BackupManifest> chain = ordered.get(i);
            boolean tooMany = keepLast != null && i >= keepLast;
            boolean tooOld = cutoff != null && chain.get(0).createdAt().isBefore(cutoff);
            if (tooMany || tooOld) {
                chain.stream().filter(b -> !b.isFull()).forEach(expired::add);
                chain.stream().filter(BackupManifest::isFull).forEach(expired::add);
            }
        }
        return expired;
    }

    private static Instant chainStart(List<BackupManifest> chain) {
        return chain.stream().filter(BackupManifest::isFull).map(BackupManifest::createdAt).findFirst()
                .orElse(chain.get(chain.size() - 1).createdAt());
    }
}
