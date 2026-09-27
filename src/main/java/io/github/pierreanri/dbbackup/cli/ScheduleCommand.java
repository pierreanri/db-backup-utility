package io.github.pierreanri.dbbackup.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.DbBackupApp;
import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.config.AppConfig;
import io.github.pierreanri.dbbackup.config.ScheduleConfig;
import io.github.pierreanri.dbbackup.core.BackupJob;
import io.github.pierreanri.dbbackup.core.BackupResult;
import io.github.pierreanri.dbbackup.db.ProcessRunner;
import io.github.pierreanri.dbbackup.db.ProcessSpec;
import io.github.pierreanri.dbbackup.logging.LoggingConfigurator;
import io.github.pierreanri.dbbackup.scheduling.CronTab;
import io.github.pierreanri.dbbackup.scheduling.ScheduledJob;
import io.github.pierreanri.dbbackup.scheduling.SchedulerDaemon;
import io.github.pierreanri.dbbackup.util.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "schedule", mixinStandardHelpOptions = true,
        header = "Run backups automatically.",
        description = "Runs the backups declared in the 'schedules' section of the configuration.",
        subcommands = {ScheduleCommand.ListJobs.class, ScheduleCommand.Daemon.class, ScheduleCommand.RunJob.class,
            ScheduleCommand.Cron.class},
        footer = {"%nTwo ways to automate backups:",
            "  - keep 'dbbackup schedule daemon' running (e.g. as a systemd service or a container), or",
            "  - let cron start each job: 'dbbackup schedule cron --install'."})
class ScheduleCommand implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduleCommand.class);

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    static List<ScheduledJob> enabledJobs(AppConfig config) {
        List<ScheduledJob> jobs = new ArrayList<>();
        for (ScheduleConfig schedule : config.schedules()) {
            if (schedule.isEnabled()) {
                jobs.add(ScheduledJob.of(schedule));
            }
        }
        return jobs;
    }

    /** Runs one scheduled backup. */
    static BackupResult runJob(AppContext ctx, ScheduleConfig schedule) {
        AppConfig config = ctx.config();
        StorageOptions storage = new StorageOptions();
        storage.storage.addAll(schedule.storage());
        Compression compression = Compression.fromName(schedule.compression() != null ? schedule.compression()
                : config.defaults().compression());
        BackupJob job = new BackupJob(config.database(schedule.database()), storage.resolve(ctx, false), compression,
                schedule.type(), schedule.scope(), schedule.tables(), schedule.retention(), true, "schedule:" + schedule.name());
        BackupResult result = ctx.backupService().backup(job);
        LOG.info("Scheduled backup {} {} in {}", result.manifest().id(),
                result.success() ? "completed" : "partially failed", FileUtils.humanDuration(result.durationMillis()));
        return result;
    }

    @Command(name = "list", aliases = "ls", mixinStandardHelpOptions = true,
            description = "Show the configured schedules and their next run.")
    static class ListJobs extends BaseCommand {

        @Override
        public Integer call() {
            AppConfig config = ctx().config();
            if (config.schedules().isEmpty()) {
                out().println("No schedules configured.");
                out().flush();
                return OK;
            }
            List<List<String>> rows = new ArrayList<>();
            for (ScheduleConfig schedule : config.schedules()) {
                ScheduledJob job = ScheduledJob.of(schedule);
                String next = schedule.isEnabled()
                        ? job.schedule().next(ctx().clock().instant().atZone(job.schedule().zone()))
                                .map(t -> Formats.time(t.toInstant())).orElse("never")
                        : "-";
                rows.add(List.of(schedule.name(), schedule.database(), schedule.type().id(), schedule.cron(),
                        job.schedule().zone().getId(),
                        next, schedule.storage().isEmpty() ? "(default)" : String.join(",", schedule.storage()),
                        schedule.isEnabled() ? "yes" : "no"));
            }
            Formats.table(out(), List.of("NAME", "DATABASE", "TYPE", "CRON", "TIME ZONE", "NEXT RUN", "STORAGE", "ENABLED"),
                    rows);
            return OK;
        }
    }

    @Command(name = "daemon", aliases = "start", mixinStandardHelpOptions = true,
            description = "Run the scheduler in the foreground until interrupted (Ctrl+C or SIGTERM).")
    static class Daemon extends BaseCommand {

        @Option(names = "--max-concurrent", paramLabel = "N", defaultValue = "1",
                description = "Maximum number of backups running at the same time (default: ${DEFAULT-VALUE}).")
        int maxConcurrent;

        @Option(names = "--shutdown-grace", paramLabel = "MINUTES", defaultValue = "30",
                description = "How long to wait for running backups on shutdown (default: ${DEFAULT-VALUE}).")
        int graceMinutes;

        @Override
        public Integer call() throws InterruptedException {
            AppContext ctx = ctx();
            List<ScheduledJob> jobs = enabledJobs(ctx.config());
            if (jobs.isEmpty()) {
                throw new DbBackupException("No enabled schedules in the configuration");
            }
            SchedulerDaemon daemon = new SchedulerDaemon(jobs, job -> runJob(ctx, job.config()), ctx.clock(),
                    maxConcurrent);
            List<List<String>> rows = new ArrayList<>();
            for (Map.Entry<String, java.time.Instant> entry : daemon.nextRuns().entrySet()) {
                rows.add(List.of(entry.getKey(), entry.getValue() == null ? "never" : Formats.time(entry.getValue())));
            }
            Formats.table(out(), List.of("JOB", "NEXT RUN"), rows);

            Thread hook = new Thread(() -> daemon.stop(Duration.ofMinutes(graceMinutes)), "dbbackup-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            daemon.start();
            daemon.awaitTermination();
            return OK;
        }
    }

    @Command(name = "run", mixinStandardHelpOptions = true, description = "Run one scheduled job now (this is what the cron entries call).")
    static class RunJob extends BaseCommand {

        @Parameters(paramLabel = "NAME", description = "Name of the schedule.")
        String name;

        @Override
        public Integer call() {
            ScheduleConfig schedule = ctx().config().schedule(name)
                    .orElseThrow(() -> new DbBackupException("Unknown schedule '" + name + "'"));
            BackupResult result = runJob(ctx(), schedule);
            out().printf("Backup %s %s in %s%n", result.manifest().id(),
                    result.success() ? "completed" : "partially failed",
                    FileUtils.humanDuration(result.durationMillis()));
            out().flush();
            return result.success() ? OK : FAILED;
        }
    }

    @Command(name = "cron", mixinStandardHelpOptions = true, description = "Print (or install) crontab entries running the enabled schedules.")
    static class Cron extends BaseCommand {

        @Option(names = "--install", description = "Add or update the entries in the current user's crontab.")
        boolean install;

        @Option(names = "--uninstall", description = "Remove the entries from the current user's crontab.")
        boolean uninstall;

        @Option(names = "--command", paramLabel = "CMD",
                description = "Command starting dbbackup (default: the java and jar running now, plus --config).")
        String command;

        @Option(names = "--log-file", paramLabel = "FILE",
                description = "File receiving the output of cron runs (default: cron.log in the log directory).")
        Path logFile;

        @Override
        public Integer call() throws IOException {
            if (install && uninstall) {
                throw new DbBackupException("--install and --uninstall cannot be combined");
            }
            AppContext ctx = ctx();
            String block = null;
            if (!uninstall) {
                List<ScheduledJob> jobs = enabledJobs(ctx.config());
                if (jobs.isEmpty()) {
                    throw new DbBackupException("No enabled schedules in the configuration");
                }
                Path log = logFile != null ? logFile
                        : LoggingConfigurator.logDirectory(ctx.config().logging(), root().logDir).resolve("cron.log");
                block = CronTab.block(jobs, command != null ? command : defaultCommand(ctx.configSource()),
                        log.toAbsolutePath().toString());
            }
            if (!install && !uninstall) {
                out().print(block);
                out().flush();
                return OK;
            }
            ProcessRunner runner = new ProcessRunner();
            String existing;
            try {
                existing = runner.run(ProcessSpec.builder(List.of("crontab", "-l"))
                        .missingHint("Is cron installed?").build()).stdout();
            } catch (DbBackupException e) {
                if (e.getMessage().contains("Cannot run")) {
                    throw e;
                }
                existing = "";
            }
            Path tmp = Files.createTempFile("dbbackup-crontab-", ".txt");
            try {
                Files.writeString(tmp, CronTab.merge(existing, block));
                runner.run(ProcessSpec.builder(List.of("crontab", tmp.toString())).build());
            } finally {
                Files.deleteIfExists(tmp);
            }
            out().println(uninstall ? "Removed the dbbackup entries from the crontab."
                    : "Installed the dbbackup entries in the crontab (see 'crontab -l').");
            out().flush();
            return OK;
        }

        static String defaultCommand(Path configSource) {
            String java = ProcessHandle.current().info().command().orElse("java");
            String base = "dbbackup";
            try {
                Path location = Path.of(DbBackupApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (location.toString().endsWith(".jar")) {
                    base = CronTab.shellQuote(java) + " -jar " + CronTab.shellQuote(location.toString());
                }
            } catch (Exception ignored) {
                // fall back to 'dbbackup' on the PATH
            }
            return configSource == null ? base
                    : base + " --config " + CronTab.shellQuote(configSource.toAbsolutePath().toString());
        }
    }
}
