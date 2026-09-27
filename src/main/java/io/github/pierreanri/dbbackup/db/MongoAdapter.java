package io.github.pierreanri.dbbackup.db;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.util.Mappers;
import io.github.pierreanri.dbbackup.util.Secrets;

/**
 * MongoDB support based on {@code mongodump}/{@code mongorestore} from the MongoDB Database Tools,
 * using the single-file archive format. The connection test uses the Java driver.
 *
 * <p>The password and connection string are passed in a private YAML file ({@code --config}), so
 * they never show up in the process list.
 */
public class MongoAdapter implements DatabaseAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(MongoAdapter.class);
    private static final String HINT = "Install the MongoDB Database Tools (mongodump, mongorestore) "
            + "or set 'binPath' for this database.";

    private final ProcessRunner runner;

    public MongoAdapter(ProcessRunner runner) {
        this.runner = runner;
    }

    @Override
    public String testConnection(DatabaseConfig db) {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString(db)))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(10, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(10, TimeUnit.SECONDS))
                .build();
        try (MongoClient client = MongoClients.create(settings)) {
            String dbName = db.database() != null ? db.database() : "admin";
            client.getDatabase(dbName).runCommand(new Document("ping", 1));
            Document info = client.getDatabase("admin").runCommand(new Document("buildInfo", 1));
            return "MongoDB " + info.getString("version");
        } catch (MongoException | IllegalArgumentException e) {
            throw new DbBackupException("Cannot connect to MongoDB " + db.describe() + ": "
                    + Secrets.redact(e.getMessage(), db.password()), e);
        }
    }

    @Override
    public DumpResult backup(BackupRequest request, Path outputFile) {
        DatabaseConfig db = request.database();
        if (request.scope() != BackupScope.FULL) {
            throw new DbBackupException("MongoDB backups only support the full scope");
        }
        if (request.tables().size() > 1) {
            throw new DbBackupException("mongodump can back up a single collection at a time; "
                    + "use one --tables value or back up the whole database");
        }
        if (!request.tables().isEmpty() && db.database() == null) {
            throw new DbBackupException("Backing up a collection requires 'database' to be set");
        }
        Path config = toolConfig(db);
        try {
            List<String> command = new ArrayList<>(List.of(Executables.resolve(db.binPath(), "mongodump"),
                    "--config=" + config));
            command.addAll(connectionArgs(db));
            command.add("--archive=" + outputFile.toAbsolutePath());
            if (db.database() != null) {
                command.add("--db=" + db.database());
            }
            if (!request.tables().isEmpty()) {
                command.add("--collection=" + request.tables().get(0));
            }
            command.addAll(db.dumpArgs());
            LOG.info("Dumping MongoDB {} with mongodump", db.database() != null ? "database '" + db.database() + "'"
                    : "server (all databases)");
            runner.run(spec(command, db).timeoutMinutes(db.timeoutMinutes()).build());
            return DumpResult.NONE;
        } finally {
            SecretFiles.deleteQuietly(config);
        }
    }

    @Override
    public void restore(RestoreRequest request, Path dumpFile) {
        DatabaseConfig db = request.database();
        String source = request.sourceDatabase();
        String target = request.targetDatabase() != null && !request.targetDatabase().isBlank()
                ? request.targetDatabase() : null;
        if (!request.tables().isEmpty() && source == null) {
            throw new DbBackupException("Restoring selected collections needs the name of the backed up database");
        }
        Path config = toolConfig(db);
        try {
            List<String> command = new ArrayList<>(List.of(Executables.resolve(db.binPath(), "mongorestore"),
                    "--config=" + config));
            command.addAll(connectionArgs(db));
            command.add("--archive=" + dumpFile.toAbsolutePath());
            if (request.clean()) {
                command.add("--drop");
            }
            if (!request.tables().isEmpty()) {
                for (String collection : request.tables()) {
                    command.add("--nsInclude=" + source + "." + collection);
                }
            } else if (source != null) {
                command.add("--nsInclude=" + source + ".*");
            }
            if (target != null && source != null && !target.equals(source)) {
                command.add("--nsFrom=" + source + ".*");
                command.add("--nsTo=" + target + ".*");
            } else if (target != null && source == null) {
                LOG.warn("The backup contains all databases: --target-database is ignored");
            }
            command.addAll(db.restoreArgs());
            LOG.info("Restoring MongoDB {} from {}", target != null ? "database '" + target + "'"
                    : source != null ? "database '" + source + "'" : "server", dumpFile.getFileName());
            runner.run(spec(command, db).timeoutMinutes(db.timeoutMinutes()).build());
        } finally {
            SecretFiles.deleteQuietly(config);
        }
    }

    @Override
    public boolean supportsSelectiveRestore() {
        return true;
    }

    /** Host, port, user and authentication database arguments; empty when a URI is configured. */
    private static List<String> connectionArgs(DatabaseConfig db) {
        List<String> args = new ArrayList<>();
        if (hasUri(db)) {
            return args;
        }
        args.add("--host=" + db.effectiveHost());
        args.add("--port=" + db.effectivePort());
        if (db.username() != null && !db.username().isBlank()) {
            args.add("--username=" + db.username());
            args.add("--authenticationDatabase=" + (db.authDatabase() != null ? db.authDatabase() : "admin"));
        }
        return args;
    }

    /** YAML file read by the database tools through {@code --config}; holds the secrets. */
    static Path toolConfig(DatabaseConfig db) {
        Map<String, String> values = new LinkedHashMap<>();
        if (hasUri(db)) {
            values.put("uri", db.uri());
        }
        if (db.hasPassword()) {
            values.put("password", db.password());
        }
        try {
            // JSON strings are valid double-quoted YAML scalars, which avoids any YAML quoting pitfall.
            StringBuilder yaml = new StringBuilder();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                yaml.append(entry.getKey()).append(": ").append(Mappers.json().writeValueAsString(entry.getValue()))
                        .append('\n');
            }
            return SecretFiles.create("dbbackup-mongo-", ".yml", yaml.length() == 0 ? "{}\n" : yaml.toString());
        } catch (JsonProcessingException e) {
            throw new DbBackupException("Cannot write MongoDB tool configuration: " + e.getMessage(), e);
        }
    }

    static String connectionString(DatabaseConfig db) {
        if (hasUri(db)) {
            return db.uri();
        }
        StringBuilder uri = new StringBuilder("mongodb://");
        if (db.username() != null && !db.username().isBlank()) {
            uri.append(encode(db.username()));
            if (db.hasPassword()) {
                uri.append(':').append(encode(db.password()));
            }
            uri.append('@');
        }
        uri.append(db.effectiveHost()).append(':').append(db.effectivePort()).append('/');
        if (db.username() != null && !db.username().isBlank()) {
            uri.append("?authSource=").append(encode(db.authDatabase() != null ? db.authDatabase() : "admin"));
        }
        return uri.toString();
    }

    private static boolean hasUri(DatabaseConfig db) {
        return db.uri() != null && !db.uri().isBlank();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static ProcessSpec.Builder spec(List<String> command, DatabaseConfig db) {
        ProcessSpec.Builder builder = ProcessSpec.builder(command).secret(db.password()).missingHint(HINT);
        if (hasUri(db)) {
            builder.secret(db.uri());
        }
        return builder;
    }
}
