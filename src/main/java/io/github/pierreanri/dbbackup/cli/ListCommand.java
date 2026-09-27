package io.github.pierreanri.dbbackup.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.github.pierreanri.dbbackup.core.BackupCatalog;
import io.github.pierreanri.dbbackup.core.BackupManifest;
import io.github.pierreanri.dbbackup.util.FileUtils;
import io.github.pierreanri.dbbackup.util.Mappers;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "list", aliases = "ls", mixinStandardHelpOptions = true,
        header = "List backups.",
        description = "Lists the backups held by the storage targets (all configured targets by default), newest first.")
class ListCommand extends BaseCommand {

    @Parameters(paramLabel = "DATABASE", arity = "0..1", description = "Only list backups of this database profile.")
    String database;

    @Mixin
    StorageOptions storageOptions;

    @Option(names = {"-n", "--limit"}, paramLabel = "N", description = "Show at most N backups per storage target.")
    Integer limit;

    @Option(names = "--json", description = "Print the manifests as JSON.")
    boolean json;

    record Entry(String storage, BackupManifest manifest) {
    }

    @Override
    public Integer call() throws JsonProcessingException {
        List<Entry> entries = new ArrayList<>();
        int failures = 0;
        for (String name : storageOptions.resolve(ctx(), true)) {
            try {
                List<BackupManifest> manifests = new BackupCatalog(ctx().storages().get(name)).list(database);
                manifests.stream().limit(limit == null ? Long.MAX_VALUE : limit)
                        .forEach(m -> entries.add(new Entry(name, m)));
            } catch (RuntimeException e) {
                failures++;
                err().println("Cannot list storage '" + name + "': " + e.getMessage());
                err().flush();
            }
        }
        entries.sort(Comparator.comparing((Entry e) -> e.manifest().createdAt()).reversed());

        if (json) {
            out().println(Mappers.json().writerWithDefaultPrettyPrinter().writeValueAsString(entries));
        } else if (entries.isEmpty()) {
            out().println("No backups found.");
        } else {
            List<List<String>> rows = new ArrayList<>();
            for (Entry entry : entries) {
                BackupManifest m = entry.manifest();
                rows.add(List.of(m.id(), m.database(), m.databaseType().id(), Formats.time(m.createdAt()),
                        FileUtils.humanSize(m.sizeBytes()), m.compression().id(), m.scope().id()
                                + (m.tables().isEmpty() ? "" : " (" + String.join(",", m.tables()) + ")"),
                        entry.storage()));
            }
            Formats.table(out(), List.of("ID", "DATABASE", "TYPE", "CREATED", "SIZE", "COMPRESSION", "SCOPE",
                    "STORAGE"), rows);
        }
        out().flush();
        return failures == 0 ? OK : FAILED;
    }
}
