package io.github.pierreanri.dbbackup.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.pierreanri.dbbackup.db.BackupScope;
import io.github.pierreanri.dbbackup.db.DatabaseType;

class ConfigLoaderTest {

    @TempDir
    Path tmp;

    private ConfigLoader loader(Map<String, String> env) {
        return new ConfigLoader(env::get, tmp, tmp.resolve("home"));
    }

    @Test
    void parsesFullConfiguration() {
        String yaml = """
                defaults:
                  compression: xz
                  storage: [local, s3]
                  retention:
                    keepLast: 7
                databases:
                  app:
                    type: postgres
                    host: db.internal
                    username: backup
                    password: ${PGPASS}
                    database: app
                  cache:
                    type: sqlite
                    file: /var/lib/cache.db
                  events:
                    type: mongo
                    uri: mongodb://u:p@localhost:27017
                storage:
                  local:
                    type: local
                    path: /backups
                  s3:
                    type: s3
                    bucket: my-bucket
                    prefix: prod
                    endpoint: http://localhost:9000
                    pathStyle: true
                    retention:
                      maxAgeDays: 30
                  gcs:
                    type: gcs
                    bucket: gcs-bucket
                  azure:
                    type: azure
                    container: backups
                    connectionString: ${AZURE_CS:-UseDevelopmentStorage=true}
                schedules:
                  - name: nightly
                    database: app
                    cron: "0 2 * * *"
                    storage: [s3]
                    scope: schema-only
                logging:
                  level: debug
                notifications:
                  slack:
                    webhookUrl: https://hooks.slack.com/services/x
                """;

        LoadedConfig loaded = loader(Map.of("PGPASS", "s3cret")).parse(yaml, null);
        AppConfig config = loaded.config();

        assertThat(loaded.warnings()).isEmpty();
        assertThat(config.databases()).containsOnlyKeys("app", "cache", "events");
        DatabaseConfig app = config.database("app");
        assertThat(app.name()).isEqualTo("app");
        assertThat(app.type()).isEqualTo(DatabaseType.POSTGRESQL);
        assertThat(app.password()).isEqualTo("s3cret");
        assertThat(app.effectivePort()).isEqualTo(5432);
        assertThat(config.database("events").type()).isEqualTo(DatabaseType.MONGODB);

        assertThat(config.storage("local")).isInstanceOf(LocalStorageConfig.class);
        S3StorageConfig s3 = (S3StorageConfig) config.storage("s3");
        assertThat(s3.pathStyle()).isTrue();
        assertThat(s3.retention().maxAgeDays()).isEqualTo(30);
        assertThat(config.storage("gcs")).isInstanceOf(GcsStorageConfig.class);
        assertThat(((AzureStorageConfig) config.storage("azure")).connectionString())
                .isEqualTo("UseDevelopmentStorage=true");

        assertThat(config.defaults().compression()).isEqualTo("xz");
        assertThat(config.defaults().retention().keepLast()).isEqualTo(7);
        ScheduleConfig nightly = config.schedule("nightly").orElseThrow();
        assertThat(nightly.scope()).isEqualTo(BackupScope.SCHEMA_ONLY);
        assertThat(nightly.isEnabled()).isTrue();
        assertThat(config.notifications().slack().webhookUrl()).startsWith("https://hooks.slack.com");
    }

    @Test
    void interpolatesVariablesDefaultsAndEscapes() {
        ConfigLoader loader = loader(Map.of("USER", "alice", "EMPTY", ""));
        List<String> warnings = new ArrayList<>();

        assertThat(loader.interpolate("${USER}", "x", warnings)).isEqualTo("alice");
        assertThat(loader.interpolate("a-${MISSING:-def}-b", "x", warnings)).isEqualTo("a-def-b");
        assertThat(loader.interpolate("${EMPTY:-fallback}", "x", warnings)).isEqualTo("fallback");
        assertThat(loader.interpolate("$${USER}", "x", warnings)).isEqualTo("${USER}");
        assertThat(loader.interpolate("p@$$w0rd\\1", "x", warnings)).isEqualTo("p@$$w0rd\\1");
        assertThat(warnings).isEmpty();

        assertThat(loader.interpolate("${NOPE}", "databases.a.password", warnings)).isEmpty();
        assertThat(warnings).containsExactly("Environment variable NOPE is not set (used in databases.a.password)");
    }

    @Test
    void numericValuesCanComeFromVariables() {
        String yaml = """
                databases:
                  db:
                    type: mysql
                    database: shop
                    port: ${DB_PORT:-3307}
                """;
        AppConfig config = loader(Map.of()).parse(yaml, null).config();
        assertThat(config.database("db").port()).isEqualTo(3307);
    }

    @Test
    void reportsUnknownProperties() {
        String yaml = """
                databases:
                  db:
                    type: mysql
                    hostname: x
                """;
        assertThatThrownBy(() -> loader(Map.of()).parse(yaml, null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("unknown property 'hostname'")
                .hasMessageContaining("databases.db");
    }

    @Test
    void reportsUnsupportedTypes() {
        assertThatThrownBy(() -> loader(Map.of()).parse("databases:\n  db:\n    type: oracle\n", null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("unsupported database type 'oracle'");
        assertThatThrownBy(() -> loader(Map.of()).parse("storage:\n  x:\n    type: ftp\n", null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("missing or unsupported storage type");
    }

    @Test
    void reportsSemanticErrors() {
        String yaml = """
                databases:
                  lite:
                    type: sqlite
                  pg:
                    type: postgresql
                    port: 70000
                storage:
                  s3:
                    type: s3
                  az:
                    type: azure
                    container: c
                defaults:
                  storage: [nowhere]
                  compression: zip
                schedules:
                  - name: a
                    database: missing
                  - name: a
                    database: lite
                    cron: "@daily"
                    retention:
                      keepLast: 0
                  - name: b
                    database: lite
                    cron: "0 25 * * *"
                encryption:
                  recipients: [age1invalid]
                  passphrase: also-set
                  scryptWorkFactor: 30
                """;
        assertThatThrownBy(() -> loader(Map.of()).parse(yaml, null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("databases.lite.file is required")
                .hasMessageContaining("databases.pg.database is required")
                .hasMessageContaining("databases.pg.port must be between")
                .hasMessageContaining("storage.s3.bucket is required")
                .hasMessageContaining("storage.az: set connectionString")
                .hasMessageContaining("defaults.storage refers to unknown storage 'nowhere'")
                .hasMessageContaining("defaults.compression: unsupported compression 'zip'")
                .hasMessageContaining("schedules.a.database refers to unknown database 'missing'")
                .hasMessageContaining("schedules.a.cron is required")
                .hasMessageContaining("schedules.a: duplicate schedule name")
                .hasMessageContaining("schedules.a.retention.keepLast must be at least 1")
                .hasMessageContaining("schedules.b: invalid cron expression '0 25 * * *'")
                .hasMessageContaining("encryption: use either recipients or a passphrase")
                .hasMessageContaining("invalid age recipient 'age1invalid'")
                .hasMessageContaining("encryption.scryptWorkFactor must be between 10 and 22");
    }

    @Test
    void reportsYamlSyntaxErrors() {
        assertThatThrownBy(() -> loader(Map.of()).parse("databases: [\n", null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Invalid YAML");
    }

    @Test
    void locatesConfigFiles() throws IOException {
        ConfigLoader loader = loader(Map.of());
        assertThat(loader.locate(null)).isEmpty();
        assertThat(loader.load(null).config().databases()).isEmpty();

        Path home = Files.createDirectories(tmp.resolve("home"));
        Path homeConfig = Files.writeString(home.resolve("config.yml"), "databases: {}\n");
        assertThat(loader.locate(null)).contains(homeConfig);

        Path local = Files.writeString(tmp.resolve("dbbackup.yml"), "databases: {}\n");
        assertThat(loader.locate(null)).contains(local);

        Path custom = Files.writeString(tmp.resolve("custom.yml"), "databases: {}\n");
        assertThat(loader(Map.of(ConfigLoader.ENV_CONFIG, custom.toString())).locate(null)).contains(custom);
        assertThat(loader.locate(custom)).contains(custom);

        assertThatThrownBy(() -> loader.locate(tmp.resolve("missing.yml")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Config file not found");
    }

    @Test
    void emptyFileGivesEmptyConfig() {
        assertThat(loader(Map.of()).parse("# nothing\n", null).config().databases()).isEmpty();
    }

    @Test
    void unknownProfileErrorsListAlternatives() {
        AppConfig config = loader(Map.of()).parse("databases:\n  a:\n    type: sqlite\n    file: a.db\n", null).config();
        assertThatThrownBy(() -> config.database("b"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Configured databases: a");
    }
}
