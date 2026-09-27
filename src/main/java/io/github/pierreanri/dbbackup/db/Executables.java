package io.github.pierreanri.dbbackup.db;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import io.github.pierreanri.dbbackup.util.PathUtils;

/**
 * Locates client tools in a configured directory or on the {@code PATH}.
 */
public final class Executables {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private Executables() {
    }

    /**
     * Returns the first of {@code names} found in {@code binPath} (when set) or on the PATH. When
     * none is found, the first name is returned unchanged so the error message stays readable.
     */
    public static String resolve(String binPath, String... names) {
        if (binPath != null && !binPath.isBlank()) {
            Path dir = PathUtils.expand(binPath);
            for (String name : names) {
                Path candidate = find(dir, name);
                if (candidate != null) {
                    return candidate.toString();
                }
            }
            return dir.resolve(names[0]).toString();
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String name : names) {
                for (String dir : path.split(File.pathSeparator)) {
                    if (!dir.isBlank() && find(Path.of(dir), name) != null) {
                        return name;
                    }
                }
            }
        }
        return names[0];
    }

    private static Path find(Path dir, String name) {
        Path candidate = dir.resolve(name);
        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
            return candidate;
        }
        if (WINDOWS) {
            Path exe = dir.resolve(name + ".exe");
            if (Files.isRegularFile(exe)) {
                return exe;
            }
        }
        return null;
    }
}
