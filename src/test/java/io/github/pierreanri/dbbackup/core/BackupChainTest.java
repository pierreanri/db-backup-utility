package io.github.pierreanri.dbbackup.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.config.AppConfig;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.config.LocalStorageConfig;
import io.github.pierreanri.dbbackup.config.RetentionConfig;
import io.github.pierreanri.dbbackup.config.StorageConfig;
import io.github.pierreanri.dbbackup.core.BackupServiceTest.MutableClock;
import io.github.pierreanri.dbbackup.db.BackupScope;
import io.github.pierreanri.dbbackup.db.BackupType;
import io.github.pierreanri.dbbackup.db.DatabaseAdapters;
import io.github.pierreanri.dbbackup.db.DatabaseType;
import io.github.pierreanri.dbbackup.logging.ActivityLog;
import io.github.pierreanri.dbbackup.notify.Notifier;
import io.github.pierreanri.dbbackup.util.Mappers;

/**
 * Incremental and differential backup chains, end to end with SQLite and local storage.
 */
class BackupChainTest {

    @TempDir
    Path tmp;

    private MutableClock clock;
    private Path dbFile;
    private DatabaseConfig db;
    private StorageRegistry storages;
    private BackupService backups;
    private RestoreService restores;

    @BeforeEach
    void setUp() throws SQLException {
        dbFile = tmp.resolve("app.db");
        sql("CREATE TABLE items (id INTEGER PRIMARY KEY, payload TEXT)");
        insertRows(0, 2000);
        db = DatabaseConfig.of("app", DatabaseType.SQLITE).withFile(dbFile.toString()).withIncremental(true);
        Map<String, StorageConfig> storage = Map.of(
                "primary", new LocalStorageConfig(tmp.resolve("primary").toString(), null),
                "mirror", new LocalStorageConfig(tmp.resolve("mirror").toString(), null));
        AppConfig config = new AppConfig(Map.of("app", db), storage, null, null, null, null, null);
        clock = new MutableClock(Instant.parse("2026-09-01T02:00:00Z"));
        storages = new StorageRegistry(config, new io.github.pierreanri.dbbackup.storage.StorageFactory());
        ActivityLog log = new ActivityLog(tmp.resolve("history.jsonl"));
        backups = new BackupService(config, new DatabaseAdapters(), storages, log, Notifier.NONE, tmp.resolve("work"),
                clock);
        restores = new RestoreService(new DatabaseAdapters(), storages, null, log, Notifier.NONE, tmp.resolve("work"),
                clock);
    }

    @AfterEach
    void tearDown() {
        storages.close();
    }

    private BackupManifest backup(BackupType type, String... targets) {
        BackupManifest manifest = backups.backup(new BackupJob(db, List.of(targets.length == 0 ? new String[] {"primary"}
                : targets), Compression.GZIP, type, BackupScope.FULL, List.of(), null, false, "cli")).manifest();
        clock.advance(Duration.ofHours(1));
        return manifest;
    }

    private RestoreResult restoreInto(String id, Path target) {
        return restores.restore(new RestoreJob(db, "primary", id, null, target.toString(), List.of(), false, true,
                List.of(), "cli"));
    }

    @Test
    void buildsAndRestoresAChain() throws Exception {
        BackupManifest full = backup(BackupType.FULL);
        insertRows(2000, 10);
        BackupManifest incr1 = backup(BackupType.INCREMENTAL);
        sql("DELETE FROM items WHERE id < 100");
        BackupManifest incr2 = backup(BackupType.INCREMENTAL);
        insertRows(5000, 3000);
        BackupManifest diff = backup(BackupType.DIFFERENTIAL);

        assertThat(full.backupType()).isEqualTo(BackupType.FULL);
        assertThat(full.checkpoint()).containsKeys("pageSize", "pageCount");
        assertThat(full.stateFile()).isEqualTo(full.id() + ".state.gz");
        assertThat(incr1.backupType()).isEqualTo(BackupType.INCREMENTAL);
        assertThat(incr1.parentId()).isEqualTo(full.id());
        assertThat(incr1.baseId()).isEqualTo(full.id());
        assertThat(incr1.fileName()).endsWith(".pages.gz");
        assertThat(incr1.rawSizeBytes()).isLessThan(full.rawSizeBytes());
        assertThat(incr2.parentId()).isEqualTo(incr1.id());
        assertThat(diff.backupType()).isEqualTo(BackupType.DIFFERENTIAL);
        assertThat(diff.parentId()).isEqualTo(full.id());
        assertThat(tmp.resolve("primary/app/" + full.stateFile())).exists();

        Path atIncr1 = tmp.resolve("r1.db");
        RestoreResult result = restoreInto(incr1.id(), atIncr1);
        assertThat(result.chain()).containsExactly(full.id(), incr1.id());
        assertThat(count(atIncr1)).isEqualTo(2010);

        Path atIncr2 = tmp.resolve("r2.db");
        assertThat(restoreInto(incr2.id(), atIncr2).chain()).containsExactly(full.id(), incr1.id(), incr2.id());
        assertThat(count(atIncr2)).isEqualTo(1910);

        Path atDiff = tmp.resolve("r3.db");
        assertThat(restoreInto("latest", atDiff).chain()).containsExactly(full.id(), diff.id());
        assertThat(count(atDiff)).isEqualTo(4910);
        assertThat(Files.readAllBytes(atDiff)).hasSameSizeAs(Files.readAllBytes(dbFile));
    }

    @Test
    void incrementalAfterADifferentialIsBasedOnIt() throws SQLException {
        BackupManifest full = backup(BackupType.FULL);
        insertRows(3000, 5);
        BackupManifest diff = backup(BackupType.DIFFERENTIAL);
        insertRows(4000, 5);
        BackupManifest incr = backup(BackupType.INCREMENTAL);

        assertThat(incr.parentId()).isEqualTo(diff.id());
        assertThat(incr.baseId()).isEqualTo(full.id());
        Path restored = tmp.resolve("r.db");
        assertThat(restoreInto(incr.id(), restored).chain()).containsExactly(full.id(), diff.id(), incr.id());
        assertThat(count(restored)).isEqualTo(2010);
    }

    @Test
    void startsWithAFullBackupWhenThereIsNothingToBuildOn() {
        BackupManifest first = backup(BackupType.INCREMENTAL);
        assertThat(first.backupType()).isEqualTo(BackupType.FULL);
        assertThat(backup(BackupType.INCREMENTAL).backupType()).isEqualTo(BackupType.INCREMENTAL);
    }

    @Test
    void fullBackupsTakenWithoutIncrementalSupportDoNotStartChains() {
        DatabaseConfig plain = db.withIncremental(false);
        BackupManifest full = backups.backup(new BackupJob(plain, List.of("primary"), Compression.GZIP, BackupType.FULL,
                BackupScope.FULL, List.of(), null, false, "cli")).manifest();
        assertThat(full.checkpoint()).isEmpty();
        assertThat(full.stateFile()).isNull();

        assertThatThrownBy(() -> backups.backup(new BackupJob(plain, List.of("primary"), Compression.GZIP,
                BackupType.INCREMENTAL, BackupScope.FULL, List.of(), null, false, "cli")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("Incremental backups are not enabled for 'app'");
        assertThat(backup(BackupType.INCREMENTAL).backupType()).isEqualTo(BackupType.FULL);
    }

    @Test
    void refusesPartialIncrementals() {
        backup(BackupType.FULL);
        assertThatThrownBy(() -> backups.backup(new BackupJob(db, List.of("primary"), Compression.GZIP,
                BackupType.INCREMENTAL, BackupScope.FULL, List.of("items"), null, false, "cli")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("cover the whole database");
    }

    @Test
    void failsOnTargetsMissingTheParent() throws SQLException {
        backup(BackupType.FULL, "primary");
        insertRows(3000, 1);
        BackupResult result = backups.backup(new BackupJob(db, List.of("primary", "mirror"), Compression.GZIP,
                BackupType.INCREMENTAL, BackupScope.FULL, List.of(), null, false, "cli"));

        assertThat(result.partial()).isTrue();
        assertThat(result.targets().get(1).error()).contains("depends on is missing here");
        assertThat(tmp.resolve("mirror/app")).doesNotExist();
    }

    @Test
    void refusesToRestoreBrokenChains() throws IOException, SQLException {
        BackupManifest full = backup(BackupType.FULL);
        insertRows(3000, 1);
        BackupManifest incr = backup(BackupType.INCREMENTAL);
        Files.delete(tmp.resolve("primary/app/" + full.id() + ".manifest.json"));

        assertThatThrownBy(() -> restoreInto(incr.id(), tmp.resolve("x.db")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("depends on " + full.id() + ", which is missing");
    }

    @Test
    void restoresALocalChainFromItsDirectory() throws SQLException {
        backup(BackupType.FULL);
        insertRows(3000, 7);
        BackupManifest incr = backup(BackupType.INCREMENTAL);
        sql("DELETE FROM items");

        restores.restore(new RestoreJob(db, null, null, tmp.resolve("primary/app/" + incr.fileName()), null, List.of(),
                false, true, List.of(), "cli"));

        assertThat(count(dbFile)).isEqualTo(2007);
    }

    @Test
    void retentionDeletesWholeChains() throws SQLException, IOException {
        BackupManifest oldFull = backup(BackupType.FULL);
        insertRows(3000, 1);
        backup(BackupType.INCREMENTAL);
        backup(BackupType.DIFFERENTIAL);
        BackupManifest newFull = backup(BackupType.FULL);
        insertRows(4000, 1);
        BackupManifest newIncr = backup(BackupType.INCREMENTAL);

        List<String> deleted = backups.applyRetention(storages.get("primary"), "app", new RetentionConfig(1, null),
                false);

        assertThat(deleted).hasSize(3).last().isEqualTo(oldFull.id());
        assertThat(new BackupCatalog(storages.get("primary")).list("app")).extracting(BackupManifest::id)
                .containsExactly(newIncr.id(), newFull.id());
        try (Stream<Path> files = Files.list(tmp.resolve("primary/app"))) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .allMatch(name -> name.startsWith(newFull.id()) || name.startsWith(newIncr.id()));
        }
    }

    @Test
    void readsVersionOneManifests() throws IOException {
        String v1 = """
                {"formatVersion":1,"id":"app-1","database":"app","databaseType":"sqlite","databaseName":"app.db",
                 "scope":"full","tables":[],"compression":"gzip","fileName":"app-1.db.gz","sizeBytes":1,
                 "rawSizeBytes":2,"sha256":"x","createdAt":"2026-01-01T00:00:00Z","durationMillis":3}
                """;
        BackupManifest manifest = Mappers.json().readValue(v1, BackupManifest.class);
        assertThat(manifest.backupType()).isEqualTo(BackupType.FULL);
        assertThat(manifest.isFull()).isTrue();
        assertThat(manifest.canStartChain()).isFalse();
        assertThat(manifest.encrypted()).isFalse();
        assertThat(manifest.method()).isEqualTo("logical");
    }

    private void insertRows(int from, int count) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile)) {
            conn.setAutoCommit(false);
            try (var ps = conn.prepareStatement("INSERT INTO items(id, payload) VALUES (?, ?)")) {
                for (int i = from; i < from + count; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "payload-" + i + "-" + "x".repeat(200));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    private void sql(String statement) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                Statement stmt = conn.createStatement()) {
            stmt.execute(statement);
        }
    }

    private static int count(Path db) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM items")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
