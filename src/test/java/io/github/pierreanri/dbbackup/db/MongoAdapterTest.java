package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;

class MongoAdapterTest {

    @TempDir
    Path tmp;

    private final RecordingRunner runner = new RecordingRunner();
    private final MongoAdapter adapter = new MongoAdapter(runner);
    private final DatabaseConfig db = DatabaseConfig.of("events", DatabaseType.MONGODB)
            .withHost("mongo1")
            .withCredentials("root", "p@ss: word")
            .withDatabase("events");

    @Test
    void dumpsArchiveWithPasswordInConfigFile() {
        Path out = tmp.resolve("events.archive");
        adapter.backup(new BackupRequest(db, BackupScope.FULL, List.of("clicks")), out);

        List<String> cmd = runner.lastCommand();
        assertThat(cmd.get(0)).endsWith("mongodump");
        assertThat(cmd).contains("--host=mongo1", "--port=27017", "--username=root", "--authenticationDatabase=admin",
                "--archive=" + out.toAbsolutePath(), "--db=events", "--collection=clicks");
        assertThat(String.join(" ", cmd)).doesNotContain("p@ss");
        assertThat(runner.credentialFiles.get("--config=")).contains("password: \"p@ss: word\"");
    }

    @Test
    void usesUriFromConfigFileWithoutHostArguments() {
        DatabaseConfig uriDb = DatabaseConfig.of("events", DatabaseType.MONGODB)
                .withUri("mongodb+srv://u:secret@cluster0.example.net/?retryWrites=true");
        adapter.backup(BackupRequest.full(uriDb), tmp.resolve("all.archive"));

        assertThat(runner.lastCommand()).noneMatch(arg -> arg.startsWith("--host") || arg.startsWith("--db"));
        assertThat(runner.credentialFiles.get("--config=")).contains("uri: \"mongodb+srv://u:secret@cluster0.example.net/?retryWrites=true\"");
        assertThat(runner.last().secrets()).contains(uriDb.uri());
    }

    @Test
    void rejectsUnsupportedBackups() {
        assertThatThrownBy(() -> adapter.backup(new BackupRequest(db, BackupScope.SCHEMA_ONLY, List.of()),
                tmp.resolve("x")))
                .isInstanceOf(DbBackupException.class);
        assertThatThrownBy(() -> adapter.backup(new BackupRequest(db, BackupScope.FULL, List.of("a", "b")),
                tmp.resolve("x")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("single collection");
    }

    @Test
    void restoresIntoRenamedDatabase() {
        Path archive = tmp.resolve("events.archive");
        adapter.restore(new RestoreRequest(db, "events_copy", "events", List.of(), true), archive);

        assertThat(runner.lastCommand()).contains("--archive=" + archive.toAbsolutePath(), "--drop",
                "--nsInclude=events.*", "--nsFrom=events.*", "--nsTo=events_copy.*");
    }

    @Test
    void restoresSelectedCollections() {
        adapter.restore(new RestoreRequest(db, null, "events", List.of("clicks", "views"), false),
                tmp.resolve("e.archive"));

        assertThat(runner.lastCommand()).contains("--nsInclude=events.clicks", "--nsInclude=events.views")
                .doesNotContain("--drop", "--nsInclude=events.*");
    }

    @Test
    void buildsConnectionStrings() {
        assertThat(MongoAdapter.connectionString(db)).isEqualTo("mongodb://root:p%40ss%3A%20word@mongo1:27017/?authSource=admin");
        assertThat(MongoAdapter.connectionString(DatabaseConfig.of("x", DatabaseType.MONGODB)))
                .isEqualTo("mongodb://localhost:27017/");
    }
}
