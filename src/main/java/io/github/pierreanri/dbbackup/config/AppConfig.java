/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Root of the configuration file.
 *
 * @param databases     database profiles by name
 * @param storage       storage targets by name
 * @param schedules     recurring backup jobs
 * @param defaults      default values
 * @param logging       logging settings
 * @param notifications notification channels
 * @param encryption    encryption of backup files
 */
public record AppConfig(
        Map<String, DatabaseConfig> databases,
        Map<String, StorageConfig> storage,
        List<ScheduleConfig> schedules,
        DefaultsConfig defaults,
        LoggingConfig logging,
        NotificationsConfig notifications,
        EncryptionConfig encryption) {

    public AppConfig {
        Map<String, DatabaseConfig> named = new LinkedHashMap<>();
        if (databases != null) {
            databases.forEach((key, value) -> named.put(key, value == null ? null : value.withName(key)));
        }
        databases = Collections.unmodifiableMap(named);
        storage = storage == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(storage));
        schedules = schedules == null ? List.of() : List.copyOf(schedules);
        defaults = defaults == null ? DefaultsConfig.EMPTY : defaults;
        logging = logging == null ? LoggingConfig.DEFAULT : logging;
        notifications = notifications == null ? NotificationsConfig.NONE : notifications;
        encryption = encryption == null ? EncryptionConfig.NONE : encryption;
    }

    public static AppConfig empty() {
        return new AppConfig(null, null, null, null, null, null, null);
    }

    public DatabaseConfig database(String name) {
        DatabaseConfig config = databases.get(name);
        if (config == null) {
            throw new ConfigException("Unknown database '" + name + "'. "
                    + (databases.isEmpty() ? "No databases are configured." : "Configured databases: "
                    + String.join(", ", databases.keySet())));
        }
        return config;
    }

    public StorageConfig storage(String name) {
        StorageConfig config = storage.get(name);
        if (config == null) {
            throw new ConfigException("Unknown storage '" + name + "'. "
                    + (storage.isEmpty() ? "No storage targets are configured." : "Configured storage targets: "
                    + String.join(", ", storage.keySet())));
        }
        return config;
    }

    public Optional<ScheduleConfig> schedule(String name) {
        return schedules.stream().filter(s -> name.equals(s.name())).findFirst();
    }
}
