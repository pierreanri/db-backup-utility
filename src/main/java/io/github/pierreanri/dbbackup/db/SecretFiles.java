package io.github.pierreanri.dbbackup.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import io.github.pierreanri.dbbackup.DbBackupException;

/**
 * Creates temporary files readable only by the current user, used to hand credentials to client
 * tools without exposing them on the command line.
 */
public final class SecretFiles {

    private SecretFiles() {
    }

    public static Path create(String prefix, String suffix, String content) {
        try {
            Path file;
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                file = Files.createTempFile(prefix, suffix,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                file = Files.createTempFile(prefix, suffix);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new DbBackupException("Cannot create temporary credentials file: " + e.getMessage(), e);
        }
    }

    public static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // best effort: the file lives in the temp directory
            }
        }
    }
}
