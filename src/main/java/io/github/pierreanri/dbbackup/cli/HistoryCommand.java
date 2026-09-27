package io.github.pierreanri.dbbackup.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.github.pierreanri.dbbackup.logging.ActivityEntry;
import io.github.pierreanri.dbbackup.logging.ActivityEntry.Status;
import io.github.pierreanri.dbbackup.util.FileUtils;
import io.github.pierreanri.dbbackup.util.Mappers;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "history", aliases = "log", mixinStandardHelpOptions = true,
        header = "Show the activity history.",
        description = "Shows the recorded backups, restores and prunes, oldest first.")
class HistoryCommand extends BaseCommand {

    @Option(names = {"-n", "--limit"}, paramLabel = "N", defaultValue = "20",
            description = "Number of entries to show (default: ${DEFAULT-VALUE}, 0 for all).")
    int limit;

    @Option(names = {"-d", "--database"}, paramLabel = "NAME", description = "Only show this database.")
    String database;

    @Option(names = "--failed", description = "Only show failed or partial operations.")
    boolean failedOnly;

    @Option(names = "--json", description = "Print entries as JSON lines.")
    boolean json;

    @Override
    public Integer call() throws JsonProcessingException {
        List<ActivityEntry> entries = new ArrayList<>();
        for (ActivityEntry entry : ctx().activityLog().readAll()) {
            if (database != null && !database.equals(entry.database())) {
                continue;
            }
            if (failedOnly && entry.status() == Status.SUCCESS) {
                continue;
            }
            entries.add(entry);
        }
        if (limit > 0 && entries.size() > limit) {
            entries = entries.subList(entries.size() - limit, entries.size());
        }
        if (json) {
            for (ActivityEntry entry : entries) {
                out().println(Mappers.json().writeValueAsString(entry));
            }
        } else if (entries.isEmpty()) {
            out().println("No activity recorded yet (" + ctx().activityLog().file() + ").");
        } else {
            List<List<String>> rows = new ArrayList<>();
            for (ActivityEntry e : entries) {
                String details = e.status() != Status.SUCCESS ? e.message()
                        : e.backupId() == null ? (e.message() != null ? e.message() : "")
                        : e.backupId() + (e.message() != null && e.operation() == ActivityEntry.Operation.BACKUP
                                ? " (" + e.message() + ")" : "");
                rows.add(List.of(Formats.time(e.timestamp()), e.operation().name().toLowerCase(Locale.ROOT),
                        String.valueOf(e.database()), e.status().name(),
                        e.sizeBytes() == null ? "-" : FileUtils.humanSize(e.sizeBytes()),
                        e.durationMillis() == null ? "-" : FileUtils.humanDuration(e.durationMillis()),
                        e.trigger() == null ? "-" : e.trigger(), details == null ? "" : details));
            }
            Formats.table(out(), List.of("TIME", "OPERATION", "DATABASE", "STATUS", "SIZE", "DURATION", "TRIGGER",
                    "DETAILS"), rows);
        }
        out().flush();
        return OK;
    }
}
