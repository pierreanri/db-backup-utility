package io.github.pierreanri.dbbackup.cli;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.pierreanri.dbbackup.config.RetentionConfig;
import io.github.pierreanri.dbbackup.core.BackupCatalog;
import io.github.pierreanri.dbbackup.core.BackupManifest;
import io.github.pierreanri.dbbackup.core.BackupService;
import io.github.pierreanri.dbbackup.logging.ActivityEntry;
import io.github.pierreanri.dbbackup.logging.ActivityEntry.Operation;
import io.github.pierreanri.dbbackup.logging.ActivityEntry.Status;
import io.github.pierreanri.dbbackup.storage.StorageBackend;
import io.github.pierreanri.dbbackup.util.HostInfo;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "prune",
        description = "Delete expired backups according to the retention rules (or --keep-last/--max-age-days).")
class PruneCommand extends BaseCommand {

    @Parameters(paramLabel = "DATABASE", arity = "0..*", description = "Database profile(s); all when omitted.")
    List<String> databases = new ArrayList<>();

    @Mixin
    StorageOptions storageOptions;

    @Mixin
    RetentionOptions retentionOptions;

    @Option(names = "--dry-run", description = "Only show what would be deleted.")
    boolean dryRun;

    @Override
    public Integer call() {
        BackupService service = ctx().backupService();
        RetentionConfig override = retentionOptions.toConfig();
        int failures = 0;
        int total = 0;
        for (String name : storageOptions.resolve(ctx(), true)) {
            StorageBackend storage = ctx().storages().get(name);
            RetentionConfig retention = service.retentionFor(name, override);
            if (retention.isEmpty()) {
                out().printf("%s: no retention rules (use --keep-last/--max-age-days or configure retention)%n", name);
                continue;
            }
            Set<String> targets = new LinkedHashSet<>(databases);
            if (targets.isEmpty()) {
                new BackupCatalog(storage).list(null).stream().map(BackupManifest::database).forEach(targets::add);
            }
            for (String database : targets) {
                Instant start = ctx().clock().instant();
                try {
                    List<String> ids = service.applyRetention(storage, database, retention, dryRun);
                    total += ids.size();
                    for (String id : ids) {
                        out().printf("%s %s from %s%n", dryRun ? "Would delete" : "Deleted", id, name);
                    }
                    if (!dryRun && !ids.isEmpty()) {
                        record(database, name, start, Status.SUCCESS, "deleted " + String.join(", ", ids));
                    }
                } catch (RuntimeException e) {
                    failures++;
                    err().printf("Pruning %s in %s failed: %s%n", database, name, e.getMessage());
                    record(database, name, start, Status.FAILED, e.getMessage());
                }
            }
        }
        out().printf("%d backup(s) %s.%n", total, dryRun ? "would be deleted" : "deleted");
        out().flush();
        err().flush();
        return failures == 0 ? OK : FAILED;
    }

    private void record(String database, String storage, Instant start, Status status, String message) {
        ctx().activityLog().append(new ActivityEntry(start, Operation.PRUNE, status, database, null, null, null,
                ctx().clock().millis() - start.toEpochMilli(), List.of(storage), "cli", message, HostInfo.hostname()));
    }
}
