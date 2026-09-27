package io.github.pierreanri.dbbackup.cli;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.config.AppConfig;
import io.github.pierreanri.dbbackup.config.DatabaseConfig;
import io.github.pierreanri.dbbackup.core.BackupJob;
import io.github.pierreanri.dbbackup.core.BackupManifest;
import io.github.pierreanri.dbbackup.core.BackupResult;
import io.github.pierreanri.dbbackup.core.BackupResult.TargetResult;
import io.github.pierreanri.dbbackup.core.BackupService;
import io.github.pierreanri.dbbackup.db.BackupScope;
import io.github.pierreanri.dbbackup.util.FileUtils;
import io.github.pierreanri.dbbackup.util.Mappers;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "backup", mixinStandardHelpOptions = true, abbreviateSynopsis = true,
        header = "Back up one or more databases.",
        description = "Dumps each database, compresses the dump, uploads it with a manifest (metadata and SHA-256) "
                + "to the storage targets and applies the retention rules.",
        footer = {"%nExamples:",
            "  dbbackup backup app",
            "  dbbackup backup --all --storage local,s3",
            "  dbbackup backup app --tables users,orders --compression xz",
            "  dbbackup backup --db-type postgresql --db-name shop --db-user postgres \\",
            "      --db-password-env PGPASS --output-dir ./backups"})
class BackupCommand extends BaseCommand {

    @Parameters(paramLabel = "DATABASE", arity = "0..*", description = "Database profile(s) to back up.")
    List<String> databases = new ArrayList<>();

    @Option(names = {"-a", "--all"}, description = "Back up every configured database.")
    boolean all;

    @Mixin
    DatabaseOptions dbOptions;

    @Mixin
    StorageOptions storageOptions;

    @Option(names = {"-C", "--compression"}, paramLabel = "ALGO",
            description = "gzip (default), bzip2, xz or none.")
    Compression compression;

    @Option(names = {"-t", "--tables"}, split = ",", paramLabel = "TABLE",
            description = "Only back up these tables (MongoDB: one collection).")
    List<String> tables = new ArrayList<>();

    @Option(names = "--schema-only", description = "Dump the schema without data.")
    boolean schemaOnly;

    @Option(names = "--data-only", description = "Dump the data without the schema.")
    boolean dataOnly;

    @Mixin
    RetentionOptions retentionOptions;

    @Option(names = "--no-retention", description = "Do not delete expired backups after this backup.")
    boolean noRetention;

    @Option(names = "--json", description = "Print the manifests as JSON.")
    boolean json;

    @Override
    public Integer call() throws JsonProcessingException {
        if (schemaOnly && dataOnly) {
            throw new DbBackupException("--schema-only and --data-only cannot be combined");
        }
        AppConfig config = ctx().config();
        List<DatabaseConfig> targets = new ArrayList<>();
        if (all) {
            if (config.databases().isEmpty()) {
                throw new DbBackupException("--all: no databases are configured");
            }
            config.databases().keySet().forEach(name -> targets.add(config.database(name)));
        } else if (databases.isEmpty()) {
            targets.add(dbOptions.resolve(config, null));
        } else {
            databases.forEach(name -> targets.add(dbOptions.resolve(config, name)));
        }

        List<String> storage = storageOptions.resolve(ctx(), false);
        Compression algo = compression != null ? compression : Compression.fromName(config.defaults().compression());
        BackupScope scope = schemaOnly ? BackupScope.SCHEMA_ONLY : dataOnly ? BackupScope.DATA_ONLY : BackupScope.FULL;
        BackupService service = ctx().backupService();

        int failures = 0;
        List<BackupManifest> manifests = new ArrayList<>();
        for (DatabaseConfig db : targets) {
            try {
                BackupResult result = service.backup(new BackupJob(db, storage, algo, scope, tables,
                        retentionOptions.toConfig(), !noRetention, "cli"));
                manifests.add(result.manifest());
                if (!json) {
                    printResult(result);
                }
                if (!result.success()) {
                    failures++;
                }
            } catch (RuntimeException e) {
                failures++;
                err().println("Backup of '" + db.name() + "' failed: " + e.getMessage());
                err().flush();
            }
        }
        if (json) {
            out().println(Mappers.json().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifests.size() == 1 ? manifests.get(0) : manifests));
        }
        out().flush();
        return failures == 0 ? OK : FAILED;
    }

    private void printResult(BackupResult result) {
        BackupManifest m = result.manifest();
        String status = result.success() ? "completed" : result.partial() ? "partially failed" : "failed";
        out().printf("Backup %s %s in %s%n", m.id(), status, FileUtils.humanDuration(result.durationMillis()));
        out().printf("  database:  %s (%s%s)%n", m.database(), m.databaseType(),
                m.databaseName() != null ? " " + m.databaseName() : "");
        out().printf("  size:      %s (raw %s, %s)%n", FileUtils.humanSize(m.sizeBytes()),
                FileUtils.humanSize(m.rawSizeBytes()), m.compression());
        out().printf("  sha256:    %s%n", m.sha256());
        String label = "  stored:    ";
        for (TargetResult target : result.targets()) {
            if (target.success()) {
                out().println(label + target.location());
            } else {
                out().println(label + "FAILED " + target.storage() + ": " + target.error());
            }
            label = "             ";
            if (!target.pruned().isEmpty()) {
                out().println("  pruned:    " + String.join(", ", target.pruned()) + " (" + target.storage() + ")");
            }
        }
    }
}
