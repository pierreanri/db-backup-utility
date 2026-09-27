package io.github.pierreanri.dbbackup.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.db.BackupScope;
import io.github.pierreanri.dbbackup.db.DatabaseType;

class RetentionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-06-30T12:00:00Z");

    static BackupManifest backup(int daysAgo) {
        Instant created = NOW.minus(Duration.ofDays(daysAgo));
        String id = BackupNaming.newId("app", created);
        return new BackupManifest(1, id, "app", DatabaseType.SQLITE, "app.db", null, BackupScope.FULL, List.of(),
                Compression.GZIP, id + ".db.gz", 10, 20, "x", created, 1, "3", "1", "h");
    }

    private static List<BackupManifest> dailyBackups(int count) {
        List<BackupManifest> backups = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            backups.add(backup(i));
        }
        return backups;
    }

    private static List<Integer> ages(List<BackupManifest> backups) {
        return backups.stream().map(b -> (int) Duration.between(b.createdAt(), NOW).toDays()).toList();
    }

    @Test
    void keepsTheMostRecentBackups() {
        assertThat(ages(new RetentionPolicy(3, null).selectExpired(dailyBackups(6), NOW))).containsExactly(3, 4, 5);
    }

    @Test
    void deletesBackupsOlderThanMaxAge() {
        assertThat(ages(new RetentionPolicy(null, 2).selectExpired(dailyBackups(5), NOW))).containsExactly(3, 4);
    }

    @Test
    void combinesBothRules() {
        assertThat(ages(new RetentionPolicy(4, 1).selectExpired(dailyBackups(6), NOW))).containsExactly(2, 3, 4, 5);
    }

    @Test
    void neverDeletesTheNewestBackup() {
        assertThat(new RetentionPolicy(null, 1).selectExpired(List.of(backup(30)), NOW)).isEmpty();
        assertThat(ages(new RetentionPolicy(null, 1).selectExpired(List.of(backup(30), backup(40)), NOW)))
                .containsExactly(40);
    }

    @Test
    void emptyPolicyKeepsEverything() {
        assertThat(new RetentionPolicy(null, null).selectExpired(dailyBackups(10), NOW)).isEmpty();
    }

    @Test
    void namesBackups() {
        assertThat(BackupNaming.newId("app", Instant.parse("2026-09-27T02:00:05Z"))).isEqualTo("app-20260927T020005Z");
        assertThat(BackupNaming.fileName("app-1", DatabaseType.POSTGRESQL, Compression.XZ)).isEqualTo("app-1.dump.xz");
        assertThat(BackupNaming.fileName("app-1", DatabaseType.MYSQL, Compression.NONE)).isEqualTo("app-1.sql");
        assertThat(BackupNaming.manifestKey("app", "app-1")).isEqualTo("app/app-1.manifest.json");
        assertThat(BackupNaming.sanitize("my db/prod")).isEqualTo("my-db-prod");
        assertThat(BackupNaming.sanitize("..")).isEqualTo("database");
    }
}
