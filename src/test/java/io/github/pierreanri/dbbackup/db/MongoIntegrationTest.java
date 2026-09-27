package io.github.pierreanri.dbbackup.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
import com.mongodb.client.MongoDatabase;

import io.github.pierreanri.dbbackup.config.DatabaseConfig;

/**
 * Runs mongodump/mongorestore against a real server. Enabled when {@code DBBACKUP_IT_MONGO_HOST}
 * is set (plus optional {@code _PORT}, {@code _USER}, {@code _PASSWORD}). The database tools must
 * be on the PATH or in {@code DBBACKUP_IT_MONGO_BIN}.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DBBACKUP_IT_MONGO_HOST", matches = ".+")
class MongoIntegrationTest {

    @TempDir
    Path tmp;

    private final MongoAdapter adapter = new MongoAdapter(new ProcessRunner());
    private DatabaseConfig db;
    private MongoClient client;

    @BeforeEach
    void createDatabase() {
        db = DatabaseConfig.of("it", DatabaseType.MONGODB)
                .withHost(System.getenv("DBBACKUP_IT_MONGO_HOST"))
                .withPort(Integer.valueOf(env("DBBACKUP_IT_MONGO_PORT", "27017")))
                .withCredentials(System.getenv("DBBACKUP_IT_MONGO_USER"), System.getenv("DBBACKUP_IT_MONGO_PASSWORD"))
                .withDatabase("dbbackup_it_" + UUID.randomUUID().toString().substring(0, 8))
                .withBinPath(System.getenv("DBBACKUP_IT_MONGO_BIN"));
        client = MongoClients.create(MongoAdapter.connectionString(db));
        MongoDatabase database = client.getDatabase(db.database());
        database.getCollection("clicks").insertMany(List.of(new Document("page", "/"), new Document("page", "/a"),
                new Document("page", "/b")));
        database.getCollection("users").insertOne(new Document("name", "ada"));
    }

    @AfterEach
    void dropDatabases() {
        for (String name : client.listDatabaseNames()) {
            if (name.startsWith(db.database())) {
                client.getDatabase(name).drop();
            }
        }
        client.close();
    }

    @Test
    void testsConnection() {
        assertThat(adapter.testConnection(db)).startsWith("MongoDB ");
    }

    @Test
    void backsUpAndRestoresIntoRenamedDatabase() {
        Path archive = tmp.resolve("db.archive");
        adapter.backup(BackupRequest.full(db), archive);

        String restored = db.database() + "_restored";
        adapter.restore(new RestoreRequest(db, restored, db.database(), List.of(), false), archive);

        assertThat(client.getDatabase(restored).getCollection("clicks").countDocuments()).isEqualTo(3);
        assertThat(client.getDatabase(restored).getCollection("users").countDocuments()).isEqualTo(1);
    }

    @Test
    void dropRestoreReplacesDocuments() {
        Path archive = tmp.resolve("db.archive");
        adapter.backup(BackupRequest.full(db), archive);
        client.getDatabase(db.database()).getCollection("clicks").deleteMany(new Document());
        client.getDatabase(db.database()).getCollection("users").insertOne(new Document("name", "extra"));

        adapter.restore(new RestoreRequest(db, null, db.database(), List.of(), true), archive);

        assertThat(client.getDatabase(db.database()).getCollection("clicks").countDocuments()).isEqualTo(3);
        assertThat(client.getDatabase(db.database()).getCollection("users").countDocuments()).isEqualTo(1);
    }

    @Test
    void restoresSelectedCollections() {
        Path archive = tmp.resolve("db.archive");
        adapter.backup(BackupRequest.full(db), archive);

        String partial = db.database() + "_partial";
        adapter.restore(new RestoreRequest(db, partial, db.database(), List.of("users"), false), archive);

        assertThat(client.getDatabase(partial).listCollectionNames().into(new java.util.ArrayList<>()))
                .containsExactly("users");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
