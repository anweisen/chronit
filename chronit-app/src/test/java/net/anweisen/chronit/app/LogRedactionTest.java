package net.anweisen.chronit.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import net.anweisen.chronit.core.util.Redactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the packaged logging configuration.
 *
 * <p>Server login passwords travel in command text and can surface in exception messages, so the
 * shipped {@code logback.xml} wraps its whole pattern in {@code %redact}. If that conversion rule
 * ever stops resolving — a rename, a logback change to the {@code conversionRule} syntax — logback
 * degrades quietly and passwords start appearing in container logs. This exercises the real file
 * rather than a rebuilt approximation of it.
 */
class LogRedactionTest {

  private LoggerContext context;

  @AfterEach
  void tearDown() {
    Redactor.clear();
    if (context != null) {
      context.stop();
    }
  }

  @Test
  void thePackagedConfigurationMasksSecrets() throws Exception {
    Redactor.register("s3cret-password");

    String rendered = renderWithPackagedConfiguration(
        "Sending /login s3cret-password to the server");

    assertFalse(rendered.contains("s3cret-password"),
        "the packaged logback.xml must mask registered secrets, but rendered: " + rendered);
    assertTrue(rendered.contains("Sending /login ***"), rendered);
  }

  @Test
  void theConversionRuleResolves() throws Exception {
    // An unresolved conversion word is rendered literally rather than failing, which is
    // exactly the kind of silent breakage this catches.
    String rendered = renderWithPackagedConfiguration("nothing sensitive here");

    assertFalse(rendered.contains("%redact"),
        "the redact conversion rule did not resolve; logback rendered it literally: " + rendered);
    assertTrue(rendered.contains("nothing sensitive here"), rendered);
  }

  private String renderWithPackagedConfiguration(String message) throws Exception {
    context = new LoggerContext();
    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);

    try (InputStream config = Objects.requireNonNull(
        getClass().getResourceAsStream("/logback.xml"), "logback.xml is not on the classpath")) {
      configurator.doConfigure(config);
    }

    ch.qos.logback.classic.Logger root = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
    ConsoleAppender<ILoggingEvent> appender =
        (ConsoleAppender<ILoggingEvent>) root.getAppender("CONSOLE");
    PatternLayoutEncoder encoder = (PatternLayoutEncoder) appender.getEncoder();

    LoggingEvent event = new LoggingEvent(
        LogRedactionTest.class.getName(), context.getLogger("net.anweisen.chronit.test"),
        Level.INFO, message, null, null);

    return new String(encoder.encode(event), StandardCharsets.UTF_8);
  }
}
