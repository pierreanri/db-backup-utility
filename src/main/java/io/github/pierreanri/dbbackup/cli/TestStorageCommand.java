/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.cli;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.storage.StorageBackend;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "test-storage", mixinStandardHelpOptions = true,
        header = "Check that storage targets are reachable and writable.",
        description = "Writes, reads back and deletes a small probe object on each storage target. "
                + "Tests every configured target when none is selected.")
class TestStorageCommand extends BaseCommand {

    @Mixin
    StorageOptions storageOptions;

    @Override
    public Integer call() {
        List<String> names = storageOptions.resolve(ctx(), true);
        int failures = 0;
        for (String name : names) {
            StorageBackend storage = ctx().storages().get(name);
            String key = ".dbbackup-probe-" + UUID.randomUUID();
            try {
                byte[] payload = ("dbbackup probe " + ctx().clock().instant()).getBytes(StandardCharsets.UTF_8);
                storage.write(key, payload);
                byte[] read = storage.read(key);
                storage.delete(key);
                if (!Arrays.equals(payload, read)) {
                    throw new DbBackupException("read back different content");
                }
                out().printf("OK    %-15s %s%n", name, storage.description());
            } catch (RuntimeException e) {
                failures++;
                out().printf("FAIL  %-15s %s%n      %s%n", name, storage.description(), e.getMessage());
            }
        }
        out().flush();
        return failures == 0 ? OK : FAILED;
    }
}
