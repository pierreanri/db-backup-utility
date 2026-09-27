/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.config.AppConfig;
import io.github.pierreanri.dbbackup.storage.LocalStorage;
import io.github.pierreanri.dbbackup.util.PathUtils;
import picocli.CommandLine.Option;

/**
 * Selection of storage targets.
 */
class StorageOptions {

    static final String OUTPUT_DIR = "output-dir";
    static final String DEFAULT_LOCAL = "default";

    private static final Logger LOG = LoggerFactory.getLogger(StorageOptions.class);

    @Option(names = {"-s", "--storage"}, split = ",", paramLabel = "NAME",
            description = "Storage target(s) from the configuration. Can be repeated or comma separated.")
    List<String> storage = new ArrayList<>();

    @Option(names = {"-o", "--output-dir"}, paramLabel = "DIR",
            description = "Use this local directory instead of a configured storage target.")
    Path outputDir;

    /**
     * Storage targets to use: {@code --output-dir}, {@code --storage}, {@code defaults.storage},
     * the only configured target, or {@code ~/.dbbackup/backups} when nothing is configured.
     *
     * @param all when no target is selected, use every configured target (for commands that read
     *            or search, like list and restore) instead of the backup defaults
     */
    List<String> resolve(AppContext ctx, boolean all) {
        AppConfig config = ctx.config();
        if (outputDir != null) {
            ctx.storages().register(new LocalStorage(OUTPUT_DIR, outputDir));
            return List.of(OUTPUT_DIR);
        }
        if (!storage.isEmpty()) {
            storage.forEach(config::storage);
            return List.copyOf(storage);
        }
        if (all && !config.storage().isEmpty()) {
            return List.copyOf(config.storage().keySet());
        }
        if (!config.defaults().storage().isEmpty()) {
            return config.defaults().storage();
        }
        if (config.storage().size() == 1) {
            return List.copyOf(config.storage().keySet());
        }
        if (config.storage().isEmpty()) {
            Path dir = PathUtils.appHome().resolve("backups");
            LOG.debug("No storage configured, using {}", dir);
            ctx.storages().register(new LocalStorage(DEFAULT_LOCAL, dir));
            return List.of(DEFAULT_LOCAL);
        }
        throw new DbBackupException("Several storage targets are configured (" + String.join(", ", config.storage().keySet())
                + "): choose with --storage or set defaults.storage");
    }
}
