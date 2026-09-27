/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.config;

import java.util.List;

import io.github.pierreanri.dbbackup.db.BackupScope;
import io.github.pierreanri.dbbackup.db.BackupType;

/**
 * A recurring backup job ({@code schedules[]} in the config file).
 *
 * @param name        unique job name
 * @param database    name of the database profile to back up
 * @param cron        5-field cron expression or a macro such as {@code @daily}
 * @param storage     storage targets; the defaults are used when empty
 * @param compression compression algorithm; the default is used when unset
 * @param type        full (default), incremental or differential
 * @param scope       full (default), schema-only or data-only
 * @param tables      only back up these tables/collections
 * @param retention   retention override for this job
 * @param timezone    time zone the cron expression is evaluated in; system zone when unset
 * @param enabled     set to {@code false} to pause the job
 */
public record ScheduleConfig(
        String name,
        String database,
        String cron,
        List<String> storage,
        String compression,
        BackupType type,
        BackupScope scope,
        List<String> tables,
        RetentionConfig retention,
        String timezone,
        Boolean enabled) {

    public ScheduleConfig {
        storage = storage == null ? List.of() : List.copyOf(storage);
        tables = tables == null ? List.of() : List.copyOf(tables);
        scope = scope == null ? BackupScope.FULL : scope;
        type = type == null ? BackupType.FULL : type;
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }
}
