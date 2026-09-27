/*
 * Copyright (c) 2026 pierreanri (https://github.com/pierreanri). All rights reserved.
 * No license is granted to use, copy, modify or distribute this file without permission.
 */
package io.github.pierreanri.dbbackup.scheduling;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the crontab block running the configured schedules with {@code dbbackup schedule run}
 * and merges it into an existing crontab.
 */
public final class CronTab {

    public static final String BEGIN = "# BEGIN dbbackup (managed by 'dbbackup schedule cron --install')";
    public static final String END = "# END dbbackup";

    private CronTab() {
    }

    /**
     * @param jobs    enabled jobs
     * @param command command prefix, e.g. {@code '/usr/bin/java' -jar '/opt/dbbackup.jar' --config '/etc/dbbackup.yml'}
     * @param logFile file receiving the output of the jobs, or {@code null}
     */
    public static String block(List<ScheduledJob> jobs, String command, String logFile) {
        StringBuilder block = new StringBuilder(BEGIN).append('\n');
        block.append("# cron runs jobs with a minimal environment: define here the variables your config needs.\n");
        String systemZone = java.time.ZoneId.systemDefault().getId();
        for (ScheduledJob job : jobs) {
            if (job.config().timezone() != null && !job.schedule().zone().getId().equals(systemZone)) {
                block.append("# note: '").append(job.name()).append("' uses time zone ").append(job.schedule().zone())
                        .append(" but cron uses the system time zone (").append(systemZone).append(")\n");
            }
            block.append(job.schedule().expression()).append(' ').append(command)
                    .append(" --quiet schedule run ").append(shellQuote(job.name()));
            if (logFile != null) {
                block.append(" >> ").append(shellQuote(logFile)).append(" 2>&1");
            }
            block.append('\n');
        }
        return block.append(END).append('\n').toString();
    }

    /** Replaces (or appends) the managed block in an existing crontab; {@code block == null} removes it. */
    public static String merge(String existing, String block) {
        List<String> kept = new ArrayList<>();
        boolean inside = false;
        for (String line : (existing == null ? "" : existing).split("\n", -1)) {
            if (line.equals(BEGIN)) {
                inside = true;
            } else if (line.equals(END)) {
                inside = false;
            } else if (!inside) {
                kept.add(line);
            }
        }
        StringBuilder result = new StringBuilder(String.join("\n", kept).strip());
        if (block != null) {
            if (result.length() > 0) {
                result.append("\n\n");
            }
            result.append(block.strip());
        }
        return result.length() == 0 ? "" : result.append('\n').toString();
    }

    public static String shellQuote(String value) {
        if (value.matches("[A-Za-z0-9_./:@%+=,-]+")) {
            return value;
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
