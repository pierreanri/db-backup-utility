/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.scheduling;

import io.github.pierreanri.dbbackup.config.ScheduleConfig;

/**
 * A schedule from the configuration with its parsed cron expression.
 */
public record ScheduledJob(ScheduleConfig config, CronSchedule schedule) {

    public static ScheduledJob of(ScheduleConfig config) {
        return new ScheduledJob(config, CronSchedule.parse(config.cron(), config.timezone()));
    }

    public String name() {
        return config.name();
    }
}
