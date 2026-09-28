package io.github.pierreanri.dbbackup.db;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.bson.BsonTimestamp;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.util.FileUtils;
import io.github.pierreanri.dbbackup.util.Mappers;
import io.github.pierreanri.dbbackup.util.Secrets;
import io.github.pierreanri.dbbackup.util.TarArchives;

/**
 * MongoDB support based on {@code mongodump}/{@code mongorestore} from the MongoDB Database Tools,
 * using the single-file archive format. The connection test uses the Java driver.
 *
 * <p>The password and connection string are passed in a private YAML file ({@code --config}), so
 * they never show up in the process list.
 *
 * <p>Incremental backups need a replica set: full backups record the latest oplog timestamp before
 * dumping, incremental/differential backups dump the oplog entries written since their parent's
 * timestamp, and restores replay them with {@code mongorestore --oplogReplay}.
 */
public class MongoAdapter implements DatabaseAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(MongoAdapter.class);
    private static final String HINT = "Install the MongoDB Database Tools (mongodump, mongorestore) "
            + "or set 'binPath' for this database.";

    static final String OPLOG_TIME = "oplogTime";
    static final String OPLOG_INCREMENT = "oplogIncrement";
    private static final String DESCRIPTOR = "dbbackup-oplog.json";

    private final ProcessRunner runner;

    public MongoAdapter(ProcessRunner runner) {
        this.runner = runner;
    }

    @Override
    public String testConnection(DatabaseConfig db) {
        try (MongoClient client = openClient(db)) {
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
        boolean startsChain = db.isIncremental() && request.tables().isEmpty();
        // Oplog entries from this point on are replayed on top of the dump (they are idempotent).
        BsonTimestamp start = startsChain ? oplogWindow(db)[1] : null;
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
            return startsChain ? DumpResult.of(checkpoint(start)) : DumpResult.NONE;
        } finally {
            SecretFiles.deleteQuietly(config);
        }
    }

    @Override
    public boolean supportsIncremental() {
        return true;
    }

    @Override
    public String fileExtension(DatabaseConfig database, BackupType type) {
        return type == BackupType.FULL ? "archive" : "oplog.tar";
    }

    /**
     * Dumps the oplog entries written since the checkpoint that concern the backed up database
     * (all databases but {@code local} and {@code config} when none is set).
     */
    @Override
    public DumpResult backupChanges(ChangesRequest request, Path outputFile) {
        DatabaseConfig db = request.database();
        BsonTimestamp from = timestamp(request.fromCheckpoint());
        BsonTimestamp[] window = oplogWindow(db);
        if (window[0].compareTo(from) > 0) {
            throw new DbBackupException("The oplog no longer goes back to the previous backup (" + describe(from)
                    + "; oldest entry " + describe(window[0]) + "): take a full backup or enlarge the oplog");
        }
        BsonTimestamp to = window[1];
        Path dir = outputFile.resolveSibling(outputFile.getFileName() + ".d");
        Path config = toolConfig(db, uriWithoutDatabase(db));
        try {
            Files.createDirectories(dir.resolve("replay"));
            Path query = dir.resolve("query.json");
            Files.writeString(query, oplogQuery(db.database(), from, to));
            List<String> command = new ArrayList<>(List.of(Executables.resolve(db.binPath(), "mongodump"),
                    "--config=" + config));
            command.addAll(connectionArgs(db));
            command.addAll(List.of("--db=local", "--collection=oplog.rs", "--queryFile=" + query,
                    "--out=" + dir.resolve("dump")));
            LOG.info("Dumping oplog entries from {} to {}", describe(from), describe(to));
            runner.run(spec(command, db).timeoutMinutes(db.timeoutMinutes()).build());

            Path dumped = dir.resolve("dump").resolve("local").resolve("oplog.rs.bson");
            Path pack = dir.resolve("pack");
            Files.createDirectories(pack.resolve("replay"));
            if (Files.exists(dumped)) {
                Files.move(dumped, pack.resolve("replay").resolve("oplog.bson"));
            } else {
                Files.createFile(pack.resolve("replay").resolve("oplog.bson"));
            }
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("format", "mongodb-oplog");
            descriptor.put("database", db.database());
            descriptor.put("from", describe(from));
            descriptor.put("to", describe(to));
            Mappers.json().writerWithDefaultPrettyPrinter().writeValue(pack.resolve(DESCRIPTOR).toFile(), descriptor);
            TarArchives.create(pack, outputFile);
            return DumpResult.of(checkpoint(to));
        } catch (IOException e) {
            throw new DbBackupException("Dumping the oplog failed: " + e.getMessage(), e);
        } finally {
            SecretFiles.deleteQuietly(config);
            FileUtils.deleteRecursively(dir);
        }
    }

    /** Restores the full dump, then replays the oplog of every change file with {@code --oplogReplay}. */
    @Override
    public void restoreChain(RestoreRequest request, Path fullDump, List<Path> changes) {
        if (!changes.isEmpty()) {
            String target = request.targetDatabase();
            if (target != null && !target.isBlank() && !target.equals(request.sourceDatabase())) {
                throw new DbBackupException("Incremental MongoDB backups can only be restored under the original "
                        + "database name (the oplog refers to it): drop --target-database");
            }
            if (!request.tables().isEmpty()) {
                throw new DbBackupException("Incremental MongoDB backups cannot be restored collection by collection");
            }
        }
        restore(request, fullDump);
        DatabaseConfig db = request.database();
        for (Path change : changes) {
            Path dir = change.resolveSibling(change.getFileName() + ".d");
            Path config = toolConfig(db);
            try {
                TarArchives.extract(change, dir);
                List<String> command = new ArrayList<>(List.of(Executables.resolve(db.binPath(), "mongorestore"),
                        "--config=" + config));
                command.addAll(connectionArgs(db));
                command.addAll(List.of("--oplogReplay", "--dir=" + dir.resolve("replay")));
                int cleaned = OplogSanitizer.stripStorageIdentifiers(dir.resolve("replay").resolve("oplog.bson"));
                LOG.info("Replaying the oplog of {}{}", change.getFileName(),
                        cleaned == 0 ? "" : " (" + cleaned + " entries without their storage identifiers)");
                runner.run(spec(command, db).timeoutMinutes(db.timeoutMinutes()).build());
            } finally {
                SecretFiles.deleteQuietly(config);
                FileUtils.deleteRecursively(dir);
            }
        }
    }

    /** Oldest and newest oplog timestamps. Needs a replica set. */
    protected BsonTimestamp[] oplogWindow(DatabaseConfig db) {
        try (MongoClient client = openClient(db)) {
            MongoCollection<Document> oplog = client.getDatabase("local").getCollection("oplog.rs");
            Document first = oplog.find().sort(new Document("$natural", 1)).limit(1).first();
            Document last = oplog.find().sort(new Document("$natural", -1)).limit(1).first();
            if (first == null || last == null) {
                throw new DbBackupException("MongoDB incremental backups need a replica set (the oplog is empty or "
                        + "missing on " + db.describe() + ")");
            }
            return new BsonTimestamp[] {first.get("ts", BsonTimestamp.class), last.get("ts", BsonTimestamp.class)};
        } catch (MongoException e) {
            throw new DbBackupException("Cannot read the oplog of " + db.describe() + " (incremental backups need a "
                    + "replica set and read access to local.oplog.rs): " + Secrets.redact(e.getMessage(), db.password()),
                    e);
        }
    }

    static String oplogQuery(String database, BsonTimestamp from, BsonTimestamp to) {
        String range = "\"ts\": {\"$gt\": " + extendedJson(from) + ", \"$lte\": " + extendedJson(to) + "}";
        if (database == null) {
            return "{" + range + ", \"ns\": {\"$not\": {\"$regex\": \"^(local|config)\\\\.\"}}}";
        }
        String prefix = "^" + database.replaceAll("[\\\\^$.|?*+()\\[\\]{}]", "\\\\\\\\$0") + "\\\\.";
        return "{" + range + ", \"$or\": [{\"ns\": {\"$regex\": \"" + prefix + "\"}}, "
                + "{\"o.applyOps.ns\": {\"$regex\": \"" + prefix + "\"}}]}";
    }

    private static String extendedJson(BsonTimestamp ts) {
        return "{\"$timestamp\": {\"t\": " + ts.getTime() + ", \"i\": " + ts.getInc() + "}}";
    }

    private static Map<String, String> checkpoint(BsonTimestamp ts) {
        return Map.of(OPLOG_TIME, String.valueOf(ts.getTime()), OPLOG_INCREMENT, String.valueOf(ts.getInc()));
    }

    private static BsonTimestamp timestamp(Map<String, String> checkpoint) {
        String time = checkpoint.get(OPLOG_TIME);
        String increment = checkpoint.get(OPLOG_INCREMENT);
        if (time == null || increment == null) {
            throw new DbBackupException("The previous backup has no oplog position: take a full backup");
        }
        return new BsonTimestamp(Integer.parseInt(time), Integer.parseInt(increment));
    }

    private static String describe(BsonTimestamp ts) {
        return java.time.Instant.ofEpochSecond(ts.getTime()) + "#" + ts.getInc();
    }

    private static MongoClient openClient(DatabaseConfig db) {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString(db)))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(10, TimeUnit.SECONDS))
                .applyToSocketSettings(b -> b.connectTimeout(10, TimeUnit.SECONDS))
                .build();
        try {
            return MongoClients.create(settings);
        } catch (IllegalArgumentException e) {
            throw new DbBackupException("Invalid MongoDB connection settings for " + db.describe() + ": "
                    + e.getMessage(), e);
        }
    }

    /**
     * The configured URI without its default database (mongodump refuses {@code --db} together with
     * a URI naming another database); the database stays the authentication source.
     */
    static String uriWithoutDatabase(DatabaseConfig db) {
        if (!hasUri(db)) {
            return null;
        }
        ConnectionString parsed = new ConnectionString(db.uri());
        if (parsed.getDatabase() == null) {
            return db.uri();
        }
        String uri = db.uri();
        int schemeEnd = uri.indexOf("://") + 3;
        int slash = uri.indexOf('/', schemeEnd);
        int query = uri.indexOf('?', slash);
        String base = uri.substring(0, slash + 1);
        String params = query < 0 ? "" : uri.substring(query + 1);
        if (!params.toLowerCase(java.util.Locale.ROOT).contains("authsource=")) {
            params = params.isEmpty() ? "authSource=" + parsed.getDatabase()
                    : params + "&authSource=" + parsed.getDatabase();
        }
        return base + "?" + params;
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
        return toolConfig(db, db.uri());
    }

    private static Path toolConfig(DatabaseConfig db, String uri) {
        Map<String, String> values = new LinkedHashMap<>();
        if (hasUri(db)) {
            values.put("uri", uri);
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
        uri.append(db.effectiveHost()).append(':').append(db.effectivePort()).append("/?directConnection=true");
        if (db.username() != null && !db.username().isBlank()) {
            uri.append("&authSource=").append(encode(db.authDatabase() != null ? db.authDatabase() : "admin"));
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
