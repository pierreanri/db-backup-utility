/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup;

import io.github.pierreanri.dbbackup.cli.RootCommand;

/**
 * Entry point of the {@code dbbackup} command-line utility.
 */
public final class DbBackupApp {

    private DbBackupApp() {
    }

    public static void main(String[] args) {
        System.exit(RootCommand.newCommandLine().execute(args));
    }
}
