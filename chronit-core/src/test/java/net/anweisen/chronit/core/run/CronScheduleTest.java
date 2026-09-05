package net.anweisen.chronit.core.run;

import net.anweisen.chronit.core.config.ConfigException;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CronScheduleTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  @Test
  void computesTheNextDailyRun() {
    CronSchedule schedule = CronSchedule.parse("0 20 * * *", BERLIN);
    ZonedDateTime from = ZonedDateTime.of(2026, 3, 1, 12, 0, 0, 0, BERLIN);

    ZonedDateTime next = schedule.nextAfter(from).orElseThrow();
    assertEquals(20, next.getHour());
    assertEquals(1, next.getDayOfMonth());
  }

  @Test
  void acceptsSixFieldsWithLeadingSeconds() {
    CronSchedule schedule = CronSchedule.parse("30 0 20 * * ?", BERLIN);
    ZonedDateTime next = schedule.nextAfter(
        ZonedDateTime.of(2026, 3, 1, 12, 0, 0, 0, BERLIN)).orElseThrow();
    assertEquals(20, next.getHour());
    assertEquals(30, next.getSecond());
  }

  /**
   * A daily job must keep firing at the same wall-clock time across a daylight saving change.
   * Europe/Berlin springs forward in the early hours of Sunday 29 March 2026, so the run on the
   * 28th is still on CET and the one on the 29th is already on CEST.
   */
  @Test
  void keepsWallClockTimeAcrossDaylightSaving() {
    CronSchedule schedule = CronSchedule.parse("0 20 * * *", BERLIN);

    ZonedDateTime beforeChange = schedule.nextAfter(
        ZonedDateTime.of(2026, 3, 27, 21, 0, 0, 0, BERLIN)).orElseThrow();
    ZonedDateTime afterChange = schedule.nextAfter(
        ZonedDateTime.of(2026, 3, 29, 12, 0, 0, 0, BERLIN)).orElseThrow();

    assertEquals(20, beforeChange.getHour(), "still 20:00 local before the change");
    assertEquals(20, afterChange.getHour(), "still 20:00 local after the change");
    // The UTC offset shifts even though the local hour does not, which is the whole point.
    assertTrue(beforeChange.getOffset().getTotalSeconds()
        != afterChange.getOffset().getTotalSeconds());
  }

  @Test
  void respectsTheConfiguredTimezone() {
    CronSchedule berlin = CronSchedule.parse("0 20 * * *", BERLIN);
    CronSchedule tokyo = CronSchedule.parse("0 20 * * *", ZoneId.of("Asia/Tokyo"));
    ZonedDateTime from = ZonedDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneId.of("UTC"));

    assertTrue(!berlin.nextAfter(from).orElseThrow().toInstant()
        .equals(tokyo.nextAfter(from).orElseThrow().toInstant()));
  }

  @Test
  void findsThePreviousFireTime() {
    CronSchedule schedule = CronSchedule.parse("0 20 * * *", BERLIN);
    Optional<ZonedDateTime> previous = schedule.lastBefore(
        ZonedDateTime.of(2026, 3, 2, 8, 0, 0, 0, BERLIN));

    assertTrue(previous.isPresent());
    assertEquals(1, previous.get().getDayOfMonth());
    assertEquals(20, previous.get().getHour());
  }

  @Test
  void rejectsInvalidExpressions() {
    assertThrows(ConfigException.class, () -> CronSchedule.parse("not a cron", BERLIN));
    assertThrows(ConfigException.class, () -> CronSchedule.parse("99 * * * *", BERLIN));
  }
}
