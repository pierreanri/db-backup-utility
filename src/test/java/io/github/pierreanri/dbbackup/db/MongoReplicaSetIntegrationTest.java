/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import io.github.pierreanri.dbbackup.config.DatabaseConfig;

/**
 * Oplog based incremental backups against a real replica set. Enabled when
 * {@code DBBACKUP_IT_MONGO_RS_HOST} is set (plus optional {@code _PORT}); the tools are taken from
 * {@code DBBACKUP_IT_MONGO_BIN} or the PATH.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DBBACKUP_IT_MONGO_RS_HOST", matches = ".+")
class MongoReplicaSetIntegrationTest {

    @TempDir
    Path tmp;

    private final MongoAdapter adapter = new MongoAdapter(new ProcessRunner());
    private DatabaseConfig db;
    private MongoClient client;

    @BeforeEach
    void setUp() {
        String port = System.getenv("DBBACKUP_IT_MONGO_RS_PORT");
        db = DatabaseConfig.of("it", DatabaseType.MONGODB)
                .withHost(System.getenv("DBBACKUP_IT_MONGO_RS_HOST"))
                .withPort(port == null || port.isBlank() ? 27017 : Integer.parseInt(port))
                .withDatabase("dbbackup_it_" + UUID.randomUUID().toString().substring(0, 8))
                .withBinPath(System.getenv("DBBACKUP_IT_MONGO_BIN"))
                .withIncremental(true);
        client = MongoClients.create(MongoAdapter.connectionString(db));
        MongoCollection<Document> orders = client.getDatabase(db.database()).getCollection("orders");
        for (int i = 0; i < 20; i++) {
            orders.insertOne(new Document("_id", i).append("total", i * 10));
        }
    }

    @AfterEach
    void tearDown() {
        client.getDatabase(db.database()).drop();
        client.getDatabase(db.database() + "_other").drop();
        client.close();
    }

    private List<Document> state() {
        List<Document> documents = new ArrayList<>();
        client.getDatabase(db.database()).getCollection("orders").find().sort(new Document("_id", 1)).into(documents);
        documents.add(new Document("audit", client.getDatabase(db.database()).getCollection("audit").countDocuments()));
        return documents;
    }

    @Test
    void incrementalAndDifferentialChainsReplayTheOplog() {
        MongoCollection<Document> orders = client.getDatabase(db.database()).getCollection("orders");
        Path full = tmp.resolve("full.archive");
        DumpResult fullResult = adapter.backup(BackupRequest.full(db), full);
        assertThat(fullResult.checkpoint()).containsKeys("oplogTime", "oplogIncrement");

        orders.insertOne(new Document("_id", 100).append("total", 1000));
        orders.updateMany(Filters.lt("_id", 10), Updates.inc("total", 1));
        client.getDatabase(db.database() + "_other").getCollection("x").insertOne(new Document("not", "mine"));
        Path incr1 = tmp.resolve("incr1.oplog.tar");
        DumpResult first = adapter.backupChanges(new ChangesRequest(db, BackupType.INCREMENTAL,
                fullResult.checkpoint(), null, "logical"), incr1);

        orders.deleteMany(Filters.gte("_id", 15));
        client.getDatabase(db.database()).getCollection("audit").insertOne(new Document("msg", "after"));
        Path incr2 = tmp.resolve("incr2.oplog.tar");
        adapter.backupChanges(new ChangesRequest(db, BackupType.INCREMENTAL, first.checkpoint(), null, "logical"),
                incr2);
        Path diff = tmp.resolve("diff.oplog.tar");
        adapter.backupChanges(new ChangesRequest(db, BackupType.DIFFERENTIAL, fullResult.checkpoint(), null,
                "logical"), diff);
        List<Document> expected = state();

        RestoreRequest restore = new RestoreRequest(db, null, db.database(), List.of(), true);
        client.getDatabase(db.database()).drop();
        adapter.restoreChain(restore, full, List.of(incr1, incr2));
        assertThat(state()).isEqualTo(expected);

        client.getDatabase(db.database()).drop();
        adapter.restoreChain(restore, full, List.of(diff));
        assertThat(state()).isEqualTo(expected);
    }
}
