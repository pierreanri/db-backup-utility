package io.github.pierreanri.dbbackup.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

/**
 * File system helpers.
 */
public final class FileUtils {

    private FileUtils() {
    }

    /** Deletes a file or directory tree; errors are ignored. */
    public static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup of temporary files
        }
    }

    /** Formats a byte count, e.g. {@code 12.3 MiB}. */
    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /** Formats a duration in milliseconds, e.g. {@code 1m 05s}. */
    public static String humanDuration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        long seconds = millis / 1000;
        if (seconds < 60) {
            return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, seconds % 60);
        }
        return String.format(Locale.ROOT, "%dh %02dm", minutes / 60, minutes % 60);
    }
}
