package io.github.pierreanri.dbbackup.cli;

import java.io.PrintWriter;
import java.nio.file.Path;

import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.logging.Verbosity;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/**
 * Top-level {@code dbbackup} command.
 */
@Command(
        name = "dbbackup",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Back up and restore MySQL/MariaDB, PostgreSQL, MongoDB and SQLite databases%n"
                + "with compression, local/cloud storage, scheduling and activity logging.%n",
        subcommands = {
            TestConnectionCommand.class,
            TestStorageCommand.class,
            BackupCommand.class,
            RestoreCommand.class,
            ListCommand.class,
            PruneCommand.class,
            HistoryCommand.class,
            ScheduleCommand.class,
            ConfigCommand.class,
            CommandLine.HelpCommand.class
        },
        footer = {"%nConfiguration is read from --config, $DBBACKUP_CONFIG, ./dbbackup.yml",
            "or ~/.dbbackup/config.yml. Run 'dbbackup config init' to create an example.",
            "Run 'dbbackup COMMAND --help' for the options of a command."})
public class RootCommand implements Runnable {

    @Spec
    CommandSpec spec;

    @Option(names = {"-c", "--config"}, paramLabel = "FILE", scope = ScopeType.INHERIT,
            description = "Configuration file.")
    Path configPath;

    @Option(names = {"-v", "--verbose"}, scope = ScopeType.INHERIT, description = "Show debug output.")
    boolean verbose;

    @Option(names = {"-q", "--quiet"}, scope = ScopeType.INHERIT, description = "Only show warnings and errors.")
    boolean quiet;

    @Option(names = "--log-dir", paramLabel = "DIR", scope = ScopeType.INHERIT,
            description = "Directory of the log file and activity history (default: ~/.dbbackup/logs).")
    Path logDir;

    private AppContext context;

    public static CommandLine newCommandLine() {
        RootCommand root = new RootCommand();
        CommandLine commandLine = new CommandLine(root)
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setUsageHelpAutoWidth(true);
        Converters.register(commandLine);
        commandLine.setExecutionStrategy(parseResult -> {
            try {
                return new CommandLine.RunLast().execute(parseResult);
            } finally {
                root.closeContext();
            }
        });
        commandLine.setExecutionExceptionHandler((ex, cmd, parseResult) -> {
            PrintWriter err = cmd.getErr();
            if (ex instanceof DbBackupException) {
                err.println("Error: " + ex.getMessage());
                LoggerFactory.getLogger(RootCommand.class).debug("Command failed", ex);
            } else {
                err.println("Unexpected error: " + ex);
                if (root.verbose) {
                    ex.printStackTrace(err);
                } else {
                    err.println("Run again with --verbose for details.");
                }
            }
            err.flush();
            return BaseCommand.FAILED;
        });
        return commandLine;
    }

    /** Lazily loads the configuration and configures logging. */
    AppContext context() {
        if (context == null) {
            Verbosity verbosity = verbose ? Verbosity.VERBOSE : quiet ? Verbosity.QUIET : Verbosity.NORMAL;
            context = AppContext.create(configPath, logDir, verbosity);
        }
        return context;
    }

    void closeContext() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
