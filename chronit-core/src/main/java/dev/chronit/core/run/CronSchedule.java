package dev.chronit.core.run;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import dev.chronit.core.config.ConfigException;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * A parsed cron expression bound to a timezone.
 *
 * <p>The timezone is part of the schedule rather than an afterthought: "every day at 20:00" means
 * local wall-clock time, so a job must still fire at 20:00 after a daylight saving change. Working
 * in {@link ZonedDateTime} throughout gets that right, whereas fixed offsets from UTC would drift
 * by an hour twice a year.
 */
public final class CronSchedule {

    private final String expression;
    private final ZoneId zone;
    private final ExecutionTime executionTime;

    private CronSchedule(String expression, ZoneId zone, ExecutionTime executionTime) {
        this.expression = expression;
        this.zone = zone;
        this.executionTime = executionTime;
    }

    /**
     * Parses a five-field cron, or six fields when the first is seconds.
     *
     * @throws ConfigException if the expression is not valid
     */
    public static CronSchedule parse(String expression, ZoneId zone) {
        int fields = expression.trim().split("\\s+").length;
        CronType type = fields >= 6 ? CronType.QUARTZ : CronType.UNIX;
        try {
            Cron cron = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(type)).parse(expression);
            cron.validate();
            return new CronSchedule(expression, zone, ExecutionTime.forCron(cron));
        } catch (RuntimeException e) {
            throw new ConfigException("Invalid cron expression '" + expression + "': " + e.getMessage(), e);
        }
    }

    public String expression() {
        return expression;
    }

    public ZoneId zone() {
        return zone;
    }

    public Optional<ZonedDateTime> nextAfter(ZonedDateTime from) {
        return executionTime.nextExecution(from.withZoneSameInstant(zone));
    }

    public Optional<ZonedDateTime> next() {
        return nextAfter(ZonedDateTime.now(zone));
    }

    public Optional<ZonedDateTime> lastBefore(ZonedDateTime from) {
        return executionTime.lastExecution(from.withZoneSameInstant(zone));
    }

    public Optional<Duration> timeUntilNext() {
        return next().map(next -> Duration.between(ZonedDateTime.now(zone), next));
    }

    @Override
    public String toString() {
        return expression + " [" + zone + "]";
    }
}
