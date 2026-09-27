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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.compression.Checksums;
import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.crypto.AgeCrypto;
import io.github.pierreanri.dbbackup.config.AppConfig;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.config.DefaultsConfig;
import io.github.pierreanri.dbbackup.config.EncryptionConfig;
import io.github.pierreanri.dbbackup.config.LocalStorageConfig;
import io.github.pierreanri.dbbackup.config.RetentionConfig;
import io.github.pierreanri.dbbackup.config.StorageConfig;
import io.github.pierreanri.dbbackup.db.BackupScope;
import io.github.pierreanri.dbbackup.db.DatabaseAdapters;
import io.github.pierreanri.dbbackup.db.DatabaseType;
import io.github.pierreanri.dbbackup.logging.ActivityEntry;
import io.github.pierreanri.dbbackup.logging.ActivityEntry.Operation;
import io.github.pierreanri.dbbackup.logging.ActivityEntry.Status;
import io.github.pierreanri.dbbackup.logging.ActivityLog;
import io.github.pierreanri.dbbackup.storage.LocalStorage;
import io.github.pierreanri.dbbackup.storage.StorageBackend;
import io.github.pierreanri.dbbackup.storage.StorageException;
import io.github.pierreanri.dbbackup.storage.StorageFactory;
import io.github.pierreanri.dbbackup.storage.StoredObject;

/**
 * End-to-end tests of the backup and restore pipeline with SQLite and local storage.
 */
class BackupServiceTest {

    @TempDir
    Path tmp;

    private final List<ActivityEntry> notifications = new ArrayList<>();
    private MutableClock clock;
    private Path dbFile;
    private DatabaseConfig db;
    private AppConfig config;
    private StorageRegistry storages;
    private ActivityLog activityLog;
    private BackupService backups;
    private RestoreService restores;

    @BeforeEach
    void setUp() throws SQLException {
        dbFile = tmp.resolve("app.db");
        sql("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT)", "INSERT INTO users(name) VALUES ('ada'), ('bob')",
                "CREATE TABLE logs (msg TEXT)", "INSERT INTO logs VALUES ('x')");
        db = DatabaseConfig.of("app", DatabaseType.SQLITE).withFile(dbFile.toString());
        Map<String, StorageConfig> storage = Map.of(
                "primary", new LocalStorageConfig(tmp.resolve("primary").toString(), null),
                "secondary", new LocalStorageConfig(tmp.resolve("secondary").toString(), new RetentionConfig(1, null)));
        config = new AppConfig(Map.of("app", db), storage, null,
                new DefaultsConfig(null, null, null, new RetentionConfig(3, null), null), null, null, null);
        clock = new MutableClock(Instant.parse("2026-09-01T02:00:00Z"));
        storages = new StorageRegistry(config, new StorageFactory());
        activityLog = new ActivityLog(tmp.resolve("logs/history.jsonl"));
        backups = new BackupService(config, new DatabaseAdapters(), storages, activityLog, notifications::add,
                tmp.resolve("work"), clock);
        restores = new RestoreService(new DatabaseAdapters(), storages, null, activityLog, notifications::add,
                tmp.resolve("work"), clock);
    }

    @AfterEach
    void tearDown() {
        storages.close();
    }

    private BackupJob job(String... storage) {
        return new BackupJob(db, List.of(storage), Compression.GZIP, BackupScope.FULL, List.of(), null, true, "cli");
    }

    @Test
    void backsUpToSeveralTargetsWithManifest() throws IOException {
        BackupResult result = backups.backup(job("primary", "secondary"));

        assertThat(result.success()).isTrue();
        BackupManifest manifest = result.manifest();
        assertThat(manifest.id()).isEqualTo("app-20260901T020000Z");
        assertThat(manifest.fileName()).isEqualTo("app-20260901T020000Z.db.gz");
        assertThat(manifest.databaseType()).isEqualTo(DatabaseType.SQLITE);
        assertThat(manifest.serverVersion()).startsWith("SQLite");

        Path stored = tmp.resolve("primary/app/app-20260901T020000Z.db.gz");
        assertThat(stored).exists();
        assertThat(tmp.resolve("secondary/app/app-20260901T020000Z.manifest.json")).exists();
        assertThat(Checksums.sha256(stored)).isEqualTo(manifest.sha256());
        assertThat(Files.size(stored)).isEqualTo(manifest.sizeBytes());
        assertThat(result.targets()).extracting(BackupResult.TargetResult::location)
                .containsExactly(stored.toString(), tmp.resolve("secondary/app/app-20260901T020000Z.db.gz").toString());

        assertThat(new BackupCatalog(storages.get("primary")).list("app")).containsExactly(manifest);
        assertThat(activityLog.readAll()).singleElement().satisfies(entry -> {
            assertThat(entry.operation()).isEqualTo(Operation.BACKUP);
            assertThat(entry.status()).isEqualTo(Status.SUCCESS);
            assertThat(entry.backupId()).isEqualTo(manifest.id());
            assertThat(entry.locations()).hasSize(2);
        });
        assertThat(notifications).hasSize(1);
        assertThat(tmp.resolve("work")).isEmptyDirectory();
    }

    @Test
    void appliesRetentionPerTarget() {
        for (int i = 0; i < 5; i++) {
            backups.backup(job("primary", "secondary"));
            clock.advance(Duration.ofHours(1));
        }
        assertThat(new BackupCatalog(storages.get("primary")).list("app")).hasSize(3);
        assertThat(new BackupCatalog(storages.get("secondary")).list("app")).hasSize(1)
                .first().extracting(BackupManifest::id).isEqualTo("app-20260901T060000Z");
        assertThat(storages.get("secondary").list("")).hasSize(2);
    }

    @Test
    void avoidsIdCollisions() {
        String first = backups.backup(job("primary")).manifest().id();
        String second = backups.backup(job("primary")).manifest().id();
        assertThat(first).isEqualTo("app-20260901T020000Z");
        assertThat(second).isEqualTo("app-20260901T020000Z-2");
    }

    @Test
    void reportsPartialFailures() {
        storages.register(new FailingStorage("broken"));

        BackupResult result = backups.backup(job("primary", "broken"));

        assertThat(result.success()).isFalse();
        assertThat(result.partial()).isTrue();
        assertThat(activityLog.readAll()).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(Status.PARTIAL);
            assertThat(entry.message()).contains("broken: disk on fire");
        });
    }

    @Test
    void recordsFailures() throws IOException {
        Files.delete(dbFile);

        assertThatThrownBy(() -> backups.backup(job("primary")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("SQLite database file not found");
        assertThat(activityLog.readAll()).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(Status.FAILED);
            assertThat(entry.message()).contains("not found");
        });
        assertThat(notifications).singleElement().extracting(ActivityEntry::status).isEqualTo(Status.FAILED);
    }

    @Test
    void restoresLatestBackupAfterDataLoss() throws SQLException {
        backups.backup(job("primary"));
        sql("DELETE FROM users", "DROP TABLE logs");

        RestoreResult result = restores.restore(new RestoreJob(db, "primary", "latest", null, null, List.of(), false,
                true, List.of(), "cli"));

        assertThat(result.manifest().id()).isEqualTo("app-20260901T020000Z");
        assertThat(count("users")).isEqualTo(2);
        assertThat(count("logs")).isEqualTo(1);
        assertThat(activityLog.readAll()).extracting(ActivityEntry::operation)
                .containsExactly(Operation.BACKUP, Operation.RESTORE);
    }

    @Test
    void restoresSelectedTablesIntoAnotherFileById() throws SQLException {
        String id = backups.backup(new BackupJob(db, List.of("primary"), Compression.XZ, BackupScope.FULL, List.of(),
                null, false, "cli")).manifest().id();
        Path copy = tmp.resolve("copy.db");

        restores.restore(new RestoreJob(db, "primary", id, null, copy.toString(), List.of("users"), false, true,
                List.of(), "cli"));

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + copy);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT group_concat(name) FROM sqlite_master WHERE type='table'")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("users");
        }
    }

    @Test
    void restoresLocalFileUsingItsManifest() throws SQLException {
        backups.backup(new BackupJob(db, List.of("primary"), Compression.BZIP2, BackupScope.FULL, List.of(), null,
                false, "cli"));
        sql("DELETE FROM users");
        Path file = tmp.resolve("primary/app/app-20260901T020000Z.db.bz2");

        RestoreResult result = restores.restore(new RestoreJob(db, null, null, file, null, List.of(), false, true,
                List.of(), "cli"));

        assertThat(result.manifest()).isNotNull();
        assertThat(count("users")).isEqualTo(2);
    }

    @Test
    void refusesCorruptedBackups() throws IOException {
        backups.backup(job("primary"));
        Path stored = tmp.resolve("primary/app/app-20260901T020000Z.db.gz");
        byte[] bytes = Files.readAllBytes(stored);
        bytes[bytes.length / 2] ^= 0x7f;
        Files.write(stored, bytes);

        assertThatThrownBy(() -> restores.restore(new RestoreJob(db, "primary", "latest", null, null, List.of(), false,
                true, List.of(), "cli")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("Checksum mismatch");
        assertThat(activityLog.readAll()).last().extracting(ActivityEntry::status).isEqualTo(Status.FAILED);
    }

    @Test
    void refusesIncompatibleDatabaseTypes() {
        backups.backup(job("primary"));
        DatabaseConfig postgres = DatabaseConfig.of("app", DatabaseType.POSTGRESQL).withDatabase("app");
        RestoreService offline = new RestoreService(new DatabaseAdapters(new io.github.pierreanri.dbbackup.db.ProcessRunner() {
            @Override
            public io.github.pierreanri.dbbackup.db.ProcessResult run(io.github.pierreanri.dbbackup.db.ProcessSpec spec) {
                return new io.github.pierreanri.dbbackup.db.ProcessResult(0, "PostgreSQL 16", "");
            }
        }), storages, null, activityLog, notifications::add, tmp.resolve("work"), clock);

        assertThatThrownBy(() -> offline.restore(new RestoreJob(postgres, "primary", "latest", null, null, List.of(),
                false, true, List.of(), "cli")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("is a sqlite backup and cannot be restored into the postgresql database");
    }

    @Test
    void reportsUnknownBackups() {
        assertThatThrownBy(() -> restores.restore(new RestoreJob(db, "primary", "app-nope", null, null, List.of(),
                false, true, List.of(), "cli")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("Backup 'app-nope' not found in storage 'primary'");
    }

    @Test
    void encryptsBackupsAndRestoresWithTheIdentity() throws Exception {
        String[] keys = AgeCrypto.generateKeyPair();
        Path identity = Files.writeString(tmp.resolve("key.txt"), keys[1] + "\n");
        AppConfig encrypted = new AppConfig(config.databases(), config.storage(), null, config.defaults(), null, null,
                new EncryptionConfig(List.of(keys[0]), null, null, null, null));
        BackupService service = new BackupService(encrypted, new DatabaseAdapters(), storages, activityLog,
                notifications::add, tmp.resolve("work"), clock);

        BackupManifest manifest = service.backup(job("primary")).manifest();
        assertThat(manifest.encryption()).isEqualTo("age");
        assertThat(manifest.fileName()).isEqualTo("app-20260901T020000Z.db.gz.age");
        Path stored = tmp.resolve("primary/app/" + manifest.fileName());
        assertThat(AgeCrypto.isEncrypted(stored)).isTrue();
        assertThat(Checksums.sha256(stored)).isEqualTo(manifest.sha256());

        sql("DELETE FROM users");
        RestoreJob restore = new RestoreJob(db, "primary", "latest", null, null, List.of(), false, true, List.of(),
                "cli");
        assertThatThrownBy(() -> restores.restore(restore))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("is encrypted");

        RestoreService withKey = new RestoreService(new DatabaseAdapters(), storages,
                new EncryptionConfig(null, null, null, List.of(identity.toString()), null), activityLog,
                notifications::add, tmp.resolve("work"), clock);
        withKey.restore(restore);
        assertThat(count("users")).isEqualTo(2);

        String[] otherKeys = AgeCrypto.generateKeyPair();
        Path wrong = Files.writeString(tmp.resolve("wrong.txt"), otherKeys[1] + "\n");
        assertThatThrownBy(() -> restores.restore(new RestoreJob(db, "primary", "latest", null, null, List.of(),
                false, true, List.of(wrong), "cli")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("Decryption of app-20260901T020000Z.db.gz.age failed");
    }

    @Test
    void encryptsWithAPassphrase() throws Exception {
        EncryptionConfig passphrase = new EncryptionConfig(null, null, "correct horse battery staple", null, 10);
        AppConfig encrypted = new AppConfig(config.databases(), config.storage(), null, config.defaults(), null, null,
                passphrase);
        new BackupService(encrypted, new DatabaseAdapters(), storages, activityLog, notifications::add,
                tmp.resolve("work"), clock).backup(new BackupJob(db, List.of("primary"), Compression.NONE,
                BackupScope.FULL, List.of(), null, false, "cli"));
        sql("DELETE FROM users");

        new RestoreService(new DatabaseAdapters(), storages, passphrase, activityLog, notifications::add,
                tmp.resolve("work"), clock).restore(new RestoreJob(db, "primary", "latest", null, null, List.of(),
                false, true, List.of(), "cli"));

        assertThat(count("users")).isEqualTo(2);
    }

    @Test
    void pruneDryRunKeepsFiles() {
        for (int i = 0; i < 3; i++) {
            backups.backup(new BackupJob(db, List.of("primary"), Compression.NONE, BackupScope.FULL, List.of(), null,
                    false, "cli"));
            clock.advance(Duration.ofDays(1));
        }
        StorageBackend primary = storages.get("primary");
        List<String> expired = backups.applyRetention(primary, "app", new RetentionConfig(1, null), true);
        assertThat(expired).hasSize(2);
        assertThat(new BackupCatalog(primary).list("app")).hasSize(3);

        backups.applyRetention(primary, "app", new RetentionConfig(1, null), false);
        assertThat(new BackupCatalog(primary).list("app")).hasSize(1);
    }

    @Test
    void resolvesRetentionPrecedence() {
        assertThat(backups.retentionFor("secondary", null)).isEqualTo(new RetentionConfig(1, null));
        assertThat(backups.retentionFor("primary", null)).isEqualTo(new RetentionConfig(3, null));
        assertThat(backups.retentionFor("secondary", new RetentionConfig(null, 9))).isEqualTo(new RetentionConfig(null, 9));
    }

    private void sql(String... statements) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
        }
    }

    private int count(String table) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Clock that tests can move forward. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Storage whose uploads always fail. */
    static final class FailingStorage extends LocalStorage {
        FailingStorage(String name) {
            super(name, Path.of("/nonexistent"));
        }

        @Override
        public void upload(Path source, String key) {
            throw new StorageException("disk on fire");
        }

        @Override
        public List<StoredObject> list(String prefix) {
            return List.of();
        }
    }
}
