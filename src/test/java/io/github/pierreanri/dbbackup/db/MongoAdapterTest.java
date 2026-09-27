/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.bson.BsonTimestamp;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.util.Mappers;

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

    /** Adapter whose oplog window is fixed instead of read from a server. */
    private MongoAdapter withOplog(int oldest, int newest) {
        return new MongoAdapter(runner) {
            @Override
            protected BsonTimestamp[] oplogWindow(DatabaseConfig config) {
                return new BsonTimestamp[] {new BsonTimestamp(oldest, 1), new BsonTimestamp(newest, 7)};
            }
        };
    }

    @Test
    void fullBackupsOfIncrementalDatabasesRecordTheOplogPosition() {
        DumpResult result = withOplog(100, 200).backup(BackupRequest.full(db.withIncremental(true)),
                tmp.resolve("e.archive"));
        assertThat(result.checkpoint()).containsEntry("oplogTime", "200").containsEntry("oplogIncrement", "7");
        assertThat(adapter.backup(BackupRequest.full(db), tmp.resolve("f.archive")).checkpoint()).isEmpty();
    }

    @Test
    void incrementalBackupsDumpTheOplogRange() {
        Path out = tmp.resolve("i.oplog.tar");
        DumpResult result = withOplog(100, 300).backupChanges(new ChangesRequest(db.withIncremental(true),
                BackupType.INCREMENTAL, Map.of("oplogTime", "200", "oplogIncrement", "7"), null, "logical"), out);

        assertThat(runner.lastCommand()).contains("--db=local", "--collection=oplog.rs")
                .anyMatch(arg -> arg.startsWith("--queryFile="));
        assertThat(result.checkpoint()).containsEntry("oplogTime", "300");
        assertThat(out).exists();

        assertThatThrownBy(() -> withOplog(250, 300).backupChanges(new ChangesRequest(db, BackupType.INCREMENTAL,
                Map.of("oplogTime", "200", "oplogIncrement", "7"), null, "logical"), tmp.resolve("x")))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("oplog no longer goes back");
    }

    @Test
    void buildsOplogQueries() throws Exception {
        JsonNode query = Mappers.json().readTree(MongoAdapter.oplogQuery("sh.op", new BsonTimestamp(1, 2),
                new BsonTimestamp(3, 4)));
        assertThat(query.at("/ts/$gt/$timestamp/t").asInt()).isEqualTo(1);
        assertThat(query.at("/ts/$lte/$timestamp/i").asInt()).isEqualTo(4);
        assertThat(query.at("/$or/0/ns/$regex").asText()).isEqualTo("^sh\\.op\\.");
        assertThat(query.at("/$or/1/o.applyOps.ns/$regex").asText()).isEqualTo("^sh\\.op\\.");

        JsonNode all = Mappers.json().readTree(MongoAdapter.oplogQuery(null, new BsonTimestamp(1, 2),
                new BsonTimestamp(3, 4)));
        assertThat(all.at("/ns/$not/$regex").asText()).isEqualTo("^(local|config)\\.");
    }

    @Test
    void incrementalChainsCannotBeRenamed() {
        assertThatThrownBy(() -> adapter.restoreChain(new RestoreRequest(db, "events_copy", "events", List.of(),
                false), tmp.resolve("full"), List.of(tmp.resolve("incr"))))
                .isInstanceOf(DbBackupException.class)
                .hasMessageContaining("original database name");
    }

    @Test
    void removesTheDatabaseFromUrisForOplogDumps() {
        assertThat(MongoAdapter.uriWithoutDatabase(db)).isNull();
        DatabaseConfig uri = DatabaseConfig.of("x", DatabaseType.MONGODB).withUri("mongodb://u:p@h:1/app?tls=true");
        assertThat(MongoAdapter.uriWithoutDatabase(uri)).isEqualTo("mongodb://u:p@h:1/?tls=true&authSource=app");
        assertThat(MongoAdapter.uriWithoutDatabase(uri.withUri("mongodb://h/app?authSource=admin")))
                .isEqualTo("mongodb://h/?authSource=admin");
        assertThat(MongoAdapter.uriWithoutDatabase(uri.withUri("mongodb://h:1/"))).isEqualTo("mongodb://h:1/");
    }

    @Test
    void buildsConnectionStrings() {
        assertThat(MongoAdapter.connectionString(db))
                .isEqualTo("mongodb://root:p%40ss%3A%20word@mongo1:27017/?directConnection=true&authSource=admin");
        assertThat(MongoAdapter.connectionString(DatabaseConfig.of("x", DatabaseType.MONGODB)))
                .isEqualTo("mongodb://localhost:27017/?directConnection=true");
    }
}
