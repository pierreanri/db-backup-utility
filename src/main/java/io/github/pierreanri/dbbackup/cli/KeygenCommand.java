/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import io.github.pierreanri.dbbackup.DbBackupException;
import io.github.pierreanri.dbbackup.crypto.AgeCrypto;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "keygen", mixinStandardHelpOptions = true,
        header = "Generate an age key pair for encrypting backups.",
        description = "Writes a new age identity (private key) and prints its public key. Put the public key in "
                + "encryption.recipients and keep the identity file safe (ideally away from the backup machine): "
                + "it is needed to restore encrypted backups. The file is compatible with the age tools.")
class KeygenCommand extends BaseCommand {

    @Option(names = {"-o", "--output"}, paramLabel = "FILE",
            description = "Write the identity to FILE (created with 0600 permissions) instead of printing it.")
    Path output;

    @Option(names = "--force", description = "Overwrite an existing file.")
    boolean force;

    @Override
    public Integer call() throws IOException {
        String[] pair = AgeCrypto.generateKeyPair();
        String content = "# created: " + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "\n"
                + "# public key: " + pair[0] + "\n"
                + pair[1] + "\n";
        if (output == null) {
            out().print(content);
            err().println("Public key: " + pair[0]);
        } else {
            if (Files.exists(output) && !force) {
                throw new DbBackupException(output + " already exists (use --force to overwrite)");
            }
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(output);
            try {
                Files.createFile(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                Files.createFile(output);
            }
            Files.writeString(output, content);
            out().println("Wrote identity to " + output.toAbsolutePath());
            out().println("Public key: " + pair[0]);
        }
        out().flush();
        err().flush();
        return OK;
    }
}
