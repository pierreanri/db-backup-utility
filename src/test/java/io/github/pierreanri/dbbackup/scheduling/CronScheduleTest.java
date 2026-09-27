/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

class CronScheduleTest {

    private static final ZonedDateTime NOON = ZonedDateTime.parse("2026-03-10T12:00:00Z");

    private static Instant next(String cron, String zone, ZonedDateTime from) {
        return CronSchedule.parse(cron, zone).next(from).orElseThrow().toInstant();
    }

    @Test
    void computesNextExecutions() {
        assertThat(next("0 2 * * *", "UTC", NOON)).isEqualTo("2026-03-11T02:00:00Z");
        assertThat(next("30 9 * * 1-5", "UTC", ZonedDateTime.parse("2026-03-13T10:00:00Z")))
                .isEqualTo("2026-03-16T09:30:00Z");
    }

    @Test
    void supportsMacros() {
        assertThat(next("@hourly", "UTC", NOON)).isEqualTo("2026-03-10T13:00:00Z");
        assertThat(next("@daily", "UTC", NOON)).isEqualTo("2026-03-11T00:00:00Z");
        assertThat(next("@weekly", "UTC", NOON)).isEqualTo("2026-03-15T00:00:00Z");
        assertThat(next("@monthly", "UTC", NOON)).isEqualTo("2026-04-01T00:00:00Z");
        assertThat(CronSchedule.parse("@yearly", "UTC").expression()).isEqualTo("@yearly");
    }

    @Test
    void evaluatesInTheConfiguredTimeZone() {
        assertThat(CronSchedule.parse("0 2 * * *", "Europe/Paris").zone()).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(next("0 2 * * *", "Europe/Paris", NOON)).isEqualTo("2026-03-11T01:00:00Z");
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> CronSchedule.parse("61 * * * *", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid cron expression '61 * * * *'");
        assertThatThrownBy(() -> CronSchedule.parse("* * *", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CronSchedule.parse("@often", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CronSchedule.parse("0 0 * * *", "Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid time zone");
    }
}
