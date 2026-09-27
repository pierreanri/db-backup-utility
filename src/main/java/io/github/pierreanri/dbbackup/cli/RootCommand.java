package io.github.pierreanri.dbbackup.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Top-level {@code dbbackup} command. Sub-commands are registered here.
 */
@Command(
        name = "dbbackup",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Back up and restore MySQL/MariaDB, PostgreSQL, MongoDB and SQLite databases%n"
                + "with compression, local/cloud storage, scheduling and activity logging.%n",
        subcommands = {CommandLine.HelpCommand.class})
public class RootCommand implements Runnable {

    @Spec
    CommandSpec spec;

    public static CommandLine newCommandLine() {
        return new CommandLine(new RootCommand())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setUsageHelpAutoWidth(true);
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
