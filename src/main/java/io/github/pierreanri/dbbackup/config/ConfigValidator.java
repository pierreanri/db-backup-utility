package io.github.pierreanri.dbbackup.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.pierreanri.dbbackup.compression.Compression;
import io.github.pierreanri.dbbackup.db.DatabaseType;
import io.github.pierreanri.dbbackup.scheduling.CronSchedule;

/**
 * Semantic validation of a parsed configuration. Returns human readable problems.
 */
public final class ConfigValidator {

    /** Profile names are used in storage keys and on the command line. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private ConfigValidator() {
    }

    public static List<String> validate(AppConfig config) {
        List<String> errors = new ArrayList<>();
        config.databases().forEach((name, db) -> validateDatabase(name, db, errors));
        config.storage().forEach((name, storage) -> validateStorage(name, storage, errors));
        validateDefaults(config, errors);
        validateSchedules(config, errors);
        validateLogging(config.logging(), errors);
        return errors;
    }

    private static void validateDatabase(String name, DatabaseConfig db, List<String> errors) {
        String where = "databases." + name;
        if (!NAME.matcher(name).matches()) {
            errors.add(where + ": invalid name (use letters, digits, '.', '_' and '-')");
        }
        if (db == null) {
            errors.add(where + ": empty definition");
            return;
        }
        if (db.type() == null) {
            errors.add(where + ".type is required (" + DatabaseType.supported() + ")");
            return;
        }
        if (db.port() != null && (db.port() < 1 || db.port() > 65535)) {
            errors.add(where + ".port must be between 1 and 65535");
        }
        if (db.timeoutMinutes() != null && db.timeoutMinutes() < 1) {
            errors.add(where + ".timeoutMinutes must be positive");
        }
        switch (db.type()) {
            case SQLITE -> {
                if (isBlank(db.file())) {
                    errors.add(where + ".file is required for sqlite databases");
                }
            }
            case MYSQL, MARIADB, POSTGRESQL -> {
                if (isBlank(db.database())) {
                    errors.add(where + ".database is required for " + db.type() + " databases");
                }
            }
            case MONGODB -> {
                // host defaults to localhost, the database is optional (all databases are dumped)
            }
        }
    }

    private static void validateStorage(String name, StorageConfig storage, List<String> errors) {
        String where = "storage." + name;
        if (!NAME.matcher(name).matches()) {
            errors.add(where + ": invalid name (use letters, digits, '.', '_' and '-')");
        }
        if (storage == null) {
            errors.add(where + ": empty definition");
            return;
        }
        validateRetention(where + ".retention", storage.retention(), errors);
        switch (storage) {
            case LocalStorageConfig local -> {
                if (isBlank(local.path())) {
                    errors.add(where + ".path is required for local storage");
                }
            }
            case S3StorageConfig s3 -> {
                if (isBlank(s3.bucket())) {
                    errors.add(where + ".bucket is required for s3 storage");
                }
                if (isBlank(s3.accessKeyId()) != isBlank(s3.secretAccessKey())) {
                    errors.add(where + ": accessKeyId and secretAccessKey must be set together");
                }
            }
            case GcsStorageConfig gcs -> {
                if (isBlank(gcs.bucket())) {
                    errors.add(where + ".bucket is required for gcs storage");
                }
            }
            case AzureStorageConfig azure -> {
                if (isBlank(azure.container())) {
                    errors.add(where + ".container is required for azure storage");
                }
                boolean hasConnectionString = !isBlank(azure.connectionString());
                boolean hasAccountAuth = !isBlank(azure.accountName())
                        && (!isBlank(azure.accountKey()) || !isBlank(azure.sasToken()));
                boolean hasEndpointSas = !isBlank(azure.endpoint()) && !isBlank(azure.sasToken());
                if (!hasConnectionString && !hasAccountAuth && !hasEndpointSas) {
                    errors.add(where + ": set connectionString, or accountName with accountKey or sasToken");
                }
            }
        }
    }

    private static void validateDefaults(AppConfig config, List<String> errors) {
        DefaultsConfig defaults = config.defaults();
        checkStorageRefs("defaults.storage", defaults.storage(), config.storage(), errors);
        checkCompression("defaults.compression", defaults.compression(), errors);
        validateRetention("defaults.retention", defaults.retention(), errors);
        if (defaults.timeoutMinutes() != null && defaults.timeoutMinutes() < 1) {
            errors.add("defaults.timeoutMinutes must be positive");
        }
    }

    private static void validateSchedules(AppConfig config, List<String> errors) {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < config.schedules().size(); i++) {
            ScheduleConfig schedule = config.schedules().get(i);
            String where = "schedules[" + i + "]";
            if (schedule == null) {
                errors.add(where + ": empty definition");
                continue;
            }
            if (isBlank(schedule.name())) {
                errors.add(where + ".name is required");
            } else {
                where = "schedules." + schedule.name();
                if (!names.add(schedule.name())) {
                    errors.add(where + ": duplicate schedule name");
                }
            }
            if (isBlank(schedule.database())) {
                errors.add(where + ".database is required");
            } else if (!config.databases().containsKey(schedule.database())) {
                errors.add(where + ".database refers to unknown database '" + schedule.database() + "'");
            }
            if (isBlank(schedule.cron())) {
                errors.add(where + ".cron is required");
            } else {
                try {
                    CronSchedule.parse(schedule.cron(), schedule.timezone());
                } catch (IllegalArgumentException e) {
                    errors.add(where + ": " + e.getMessage());
                }
            }
            checkStorageRefs(where + ".storage", schedule.storage(), config.storage(), errors);
            checkCompression(where + ".compression", schedule.compression(), errors);
            validateRetention(where + ".retention", schedule.retention(), errors);
        }
    }

    private static void validateLogging(LoggingConfig logging, List<String> errors) {
        if (logging.level() != null
                && !Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF").contains(logging.level().toUpperCase())) {
            errors.add("logging.level must be one of TRACE, DEBUG, INFO, WARN, ERROR, OFF");
        }
        if (logging.maxHistoryDays() != null && logging.maxHistoryDays() < 1) {
            errors.add("logging.maxHistoryDays must be positive");
        }
    }

    private static void validateRetention(String where, RetentionConfig retention, List<String> errors) {
        if (retention == null) {
            return;
        }
        if (retention.keepLast() != null && retention.keepLast() < 1) {
            errors.add(where + ".keepLast must be at least 1");
        }
        if (retention.maxAgeDays() != null && retention.maxAgeDays() < 1) {
            errors.add(where + ".maxAgeDays must be at least 1");
        }
    }

    private static void checkCompression(String where, String compression, List<String> errors) {
        if (compression != null) {
            try {
                Compression.fromName(compression);
            } catch (IllegalArgumentException e) {
                errors.add(where + ": " + e.getMessage());
            }
        }
    }

    private static void checkStorageRefs(String where, List<String> refs, Map<String, StorageConfig> storage,
            List<String> errors) {
        for (String ref : refs) {
            if (!storage.containsKey(ref)) {
                errors.add(where + " refers to unknown storage '" + ref + "'");
            }
        }
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
