/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.AppConfig;
import io.github.pierreanri.dbbackup.config.ConfigLoader;
import io.github.pierreanri.dbbackup.config.LoadedConfig;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "config", mixinStandardHelpOptions = true, header = "Create or check the configuration file.",
        description = "Creates an annotated example configuration or checks an existing one.",
        subcommands = {ConfigCommand.Init.class, ConfigCommand.Validate.class})
class ConfigCommand implements Runnable {

    @picocli.CommandLine.Spec
    picocli.CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(name = "init", mixinStandardHelpOptions = true, description = "Write an annotated example configuration file.")
    static class Init extends BaseCommand {

        @Parameters(paramLabel = "FILE", arity = "0..1",
                description = "Where to write it (default: --config or ~/.dbbackup/config.yml).")
        Path target;

        @Option(names = "--force", description = "Overwrite an existing file.")
        boolean force;

        @Override
        public Integer call() throws IOException {
            Path file = target != null ? target : root().configPath != null ? root().configPath
                    : new ConfigLoader().defaultLocation();
            if (Files.exists(file) && !force) {
                throw new DbBackupException(file + " already exists (use --force to overwrite)");
            }
            try (InputStream example = ConfigCommand.class.getResourceAsStream("/dbbackup.example.yml")) {
                if (example == null) {
                    throw new DbBackupException("The example configuration is missing from the application");
                }
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(file, example.readAllBytes());
            }
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
            out().println("Wrote " + file.toAbsolutePath());
            out().println("Edit it, then run 'dbbackup config validate' and 'dbbackup test-connection'.");
            out().flush();
            return OK;
        }
    }

    @Command(name = "validate", aliases = "check", mixinStandardHelpOptions = true, description = "Check the configuration file for errors.")
    static class Validate extends BaseCommand {

        @Override
        public Integer call() {
            ConfigLoader loader = new ConfigLoader();
            LoadedConfig loaded = loader.load(root().configPath);
            if (loaded.source() == null) {
                out().println("No configuration file found (looked for $" + ConfigLoader.ENV_CONFIG
                        + ", ./dbbackup.yml, " + loader.defaultLocation() + ").");
                out().flush();
                return FAILED;
            }
            AppConfig config = loaded.config();
            out().printf("Configuration %s is valid: %d database(s), %d storage target(s), %d schedule(s).%n",
                    loaded.source(), config.databases().size(), config.storage().size(), config.schedules().size());
            for (String warning : loaded.warnings()) {
                out().println("warning: " + warning);
            }
            out().flush();
            return OK;
        }
    }
}
