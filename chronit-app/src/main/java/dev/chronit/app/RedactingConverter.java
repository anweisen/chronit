package dev.chronit.app;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;
import dev.chronit.core.util.Redactor;

/**
 * Masks configured secrets in rendered log lines.
 *
 * <p>Call sites that knowingly log a password already redact it themselves, but secrets also reach
 * the log by less obvious routes — an exception message quoting the command that failed, a server
 * echoing back what it received. Wrapping the whole pattern catches those without needing every
 * future call site to remember.
 */
public final class RedactingConverter extends CompositeConverter<ILoggingEvent> {

    @Override
    protected String transform(ILoggingEvent event, String rendered) {
        return Redactor.redact(rendered);
    }
}
