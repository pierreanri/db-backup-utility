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
