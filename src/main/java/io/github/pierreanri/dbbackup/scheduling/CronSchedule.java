package io.github.pierreanri.dbbackup.scheduling;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

/**
 * A standard 5-field cron expression ({@code minute hour day-of-month month day-of-week}) or one
 * of the macros {@code @hourly}, {@code @daily}, {@code @weekly}, {@code @monthly} and
 * {@code @yearly}, evaluated in a time zone.
 */
public final class CronSchedule {

    private static final CronParser PARSER = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
    private static final Map<String, String> MACROS = Map.of(
            "@yearly", "0 0 1 1 *",
            "@annually", "0 0 1 1 *",
            "@monthly", "0 0 1 * *",
            "@weekly", "0 0 * * 0",
            "@daily", "0 0 * * *",
            "@midnight", "0 0 * * *",
            "@hourly", "0 * * * *");

    private final String expression;
    private final ZoneId zone;
    private final ExecutionTime executionTime;

    private CronSchedule(String expression, ZoneId zone, ExecutionTime executionTime) {
        this.expression = expression;
        this.zone = zone;
        this.executionTime = executionTime;
    }

    /**
     * @param expression cron expression or macro
     * @param timezone   IANA time zone id; the system zone when {@code null}
     * @throws IllegalArgumentException when the expression or the zone is invalid
     */
    public static CronSchedule parse(String expression, String timezone) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("empty cron expression");
        }
        String trimmed = expression.trim();
        String effective = MACROS.getOrDefault(trimmed.toLowerCase(Locale.ROOT), trimmed);
        ZoneId zone;
        try {
            zone = timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("invalid time zone '" + timezone + "'");
        }
        try {
            Cron cron = PARSER.parse(effective).validate();
            return new CronSchedule(trimmed, zone, ExecutionTime.forCron(cron));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid cron expression '" + expression + "': " + e.getMessage());
        }
    }

    /** Next execution strictly after {@code after}. */
    public Optional<ZonedDateTime> next(ZonedDateTime after) {
        return executionTime.nextExecution(after.withZoneSameInstant(zone));
    }

    public String expression() {
        return expression;
    }

    public ZoneId zone() {
        return zone;
    }
}
