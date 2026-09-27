/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import io.github.pierreanri.dbbackup.DbBackupException;

/**
 * Minimal tar support used to bundle several files (binary logs, oplog, data directories) into a
 * single backup file. File modes are preserved on POSIX systems.
 */
public final class TarArchives {

    private TarArchives() {
    }

    /** Archives the whole content of {@code directory} (paths relative to it) into {@code tarFile}. */
    public static void create(Path directory, Path tarFile) {
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(tarFile), 1 << 20);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(file);
                Stream<Path> paths = Files.walk(directory)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            List<Path> sorted = paths.filter(p -> !p.equals(directory)).sorted().toList();
            for (Path path : sorted) {
                String name = directory.relativize(path).toString().replace('\\', '/');
                boolean isDirectory = Files.isDirectory(path);
                TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), isDirectory ? name + "/" : name);
                mode(path).ifPresent(entry::setMode);
                tar.putArchiveEntry(entry);
                if (!isDirectory) {
                    Files.copy(path, tar);
                }
                tar.closeArchiveEntry();
            }
            tar.finish();
        } catch (IOException e) {
            throw new DbBackupException("Cannot create archive " + tarFile.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** Extracts {@code tarFile} into {@code directory}, refusing entries that would escape it. */
    public static void extract(Path tarFile, Path directory) {
        try (InputStream file = new BufferedInputStream(Files.newInputStream(tarFile), 1 << 20);
                TarArchiveInputStream tar = new TarArchiveInputStream(file)) {
            Path root = directory.toAbsolutePath().normalize();
            Files.createDirectories(root);
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new DbBackupException("Refusing archive entry outside the target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else if (entry.isFile()) {
                    Files.createDirectories(target.getParent());
                    Files.copy(tar, target);
                } else {
                    continue;
                }
                setMode(target, entry.getMode());
            }
        } catch (IOException e) {
            throw new DbBackupException("Cannot extract archive " + tarFile.getFileName() + ": " + e.getMessage(), e);
        }
    }

    private static java.util.Optional<Integer> mode(Path path) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            int mode = 0;
            for (PosixFilePermission permission : permissions) {
                mode |= 1 << (8 - permission.ordinal());
            }
            return java.util.Optional.of(mode | (Files.isDirectory(path) ? 040000 : 0100000));
        } catch (IOException | UnsupportedOperationException e) {
            return java.util.Optional.empty();
        }
    }

    private static void setMode(Path path, int mode) {
        StringBuilder text = new StringBuilder();
        String letters = "rwxrwxrwx";
        for (int i = 0; i < 9; i++) {
            text.append((mode & (1 << (8 - i))) != 0 ? letters.charAt(i) : '-');
        }
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(text.toString()));
        } catch (IOException | UnsupportedOperationException e) {
            // not a POSIX file system: keep the defaults
        }
    }
}
