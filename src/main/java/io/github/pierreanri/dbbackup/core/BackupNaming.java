package io.github.pierreanri.dbbackup.core;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.crypto.AgeCrypto;
import io.github.pierreanri.dbbackup.db.DatabaseType;

/**
 * Naming scheme of backups in storage:
 * <pre>
 * &lt;database&gt;/&lt;database&gt;-20260927T020000Z.dump.gz
 * &lt;database&gt;/&lt;database&gt;-20260927T020000Z.manifest.json
 * </pre>
 */
public final class BackupNaming {

    public static final String MANIFEST_SUFFIX = ".manifest.json";
    public static final String STATE_SUFFIX = ".state.gz";

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private BackupNaming() {
    }

    public static String newId(String database, Instant time) {
        return database + "-" + TIMESTAMP.format(time);
    }

    public static String fileName(String id, DatabaseType type, Compression compression) {
        return fileName(id, type, compression, false);
    }

    public static String fileName(String id, DatabaseType type, Compression compression, boolean encrypted) {
        return fileName(id, type.fileExtension(), compression, encrypted);
    }

    public static String fileName(String id, String extension, Compression compression, boolean encrypted) {
        return id + "." + extension + compression.extension() + (encrypted ? AgeCrypto.EXTENSION : "");
    }

    public static String manifestKey(String database, String id) {
        return database + "/" + id + MANIFEST_SUFFIX;
    }

    /** Turns an arbitrary string (database name, file name) into a valid profile name. */
    public static String sanitize(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-._]+", "");
        return cleaned.isEmpty() ? "database" : cleaned;
    }
}
