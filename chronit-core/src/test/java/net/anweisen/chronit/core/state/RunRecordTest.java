package net.anweisen.chronit.core.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run history is an append-only file that outlives any one build, so the status fields added
 * to it have to be optional on the way in and derived when they are absent.
 */
class RunRecordTest {

  private static final Instant START = Instant.parse("2026-08-12T18:00:00Z");
  private static final Instant END = Instant.parse("2026-08-12T18:30:00Z");

  private static RunRecord.VisitRecord visit(String server, boolean success, String outcome) {
    return new RunRecord.VisitRecord(server, "main", START, Duration.ofMinutes(5),
        success, "detail", 1, 1, 776, false, success ? Duration.ofSeconds(3) : null, outcome);
  }

  @Test
  void everyVisitSucceedingIsASuccess() {
    RunRecord record = new RunRecord("r", "nightly", "schedule", START, END,
        List.of(visit("a", true, "CLIENT_CLOSED"), visit("b", true, "CLIENT_CLOSED")));

    assertEquals(RunStatus.SUCCEEDED, record.status());
    assertTrue(record.succeeded());
    assertFalse(record.status().isProblem());
  }

  @Test
  void someWorkingAndSomeNotIsPartialRatherThanFailed() {
    RunRecord record = new RunRecord("r", "nightly", "schedule", START, END,
        List.of(visit("a", true, "CLIENT_CLOSED"), visit("b", false, "KICKED")));

    assertEquals(RunStatus.PARTIAL, record.status());
    assertFalse(record.succeeded());
    assertTrue(record.status().isProblem());
  }

  @Test
  void nothingWorkingIsAFailure() {
    RunRecord record = new RunRecord("r", "nightly", "schedule", START, END,
        List.of(visit("a", false, "NETWORK")));

    assertEquals(RunStatus.FAILED, record.status());
  }

  /**
   * The pre-status shape recorded a stopped visit as a failure whose outcome happened to be
   * CANCELLED. Old history has to keep reading as stopped rather than as a fault.
   */
  @Test
  void historyWrittenBeforeStatusesExistedStillReadsCorrectly() {
    RunRecord record = new RunRecord("r", "nightly", "web", START, END,
        List.of(visit("a", false, "CANCELLED")));

    assertEquals(VisitStatus.CANCELLED, record.visits().getFirst().status());
    assertEquals(RunStatus.CANCELLED, record.status());
  }

  /** Skipped visits are not failures, so they must not drag the run's status down with them. */
  @Test
  void skippedVisitsCountSeparatelyFromFailures() {
    RunRecord record = new RunRecord("r", "nightly", "schedule", START, END,
        List.of(visit("a", true, "CLIENT_CLOSED"),
            RunRecord.VisitRecord.skipped("b", "main", "Never attempted")),
        null);

    assertEquals(RunStatus.SUCCEEDED, record.status());
    assertEquals(1, record.attemptedCount());
    assertEquals(1, record.skippedCount());
    assertEquals(1, record.successCount());
  }

  /** An explicit status always wins: it is the only thing that knows a job was stopped. */
  @Test
  void anExplicitStatusIsNotSecondGuessed() {
    RunRecord record = new RunRecord("r", "nightly", "web", START, END,
        List.of(visit("a", true, "CLIENT_CLOSED")), RunStatus.CANCELLED);

    assertEquals(RunStatus.CANCELLED, record.status());
    assertFalse(record.succeeded());
  }

  @Test
  void survivesARoundTripThroughTheHistoryFormat() throws Exception {
    ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    RunRecord record = new RunRecord("r", "nightly", "web", START, END,
        List.of(visit("a", true, "CLIENT_CLOSED"),
            RunRecord.VisitRecord.skipped("b", "main", "stopped")),
        RunStatus.CANCELLED);

    RunRecord back = mapper.readValue(mapper.writeValueAsString(record), RunRecord.class);

    assertEquals(RunStatus.CANCELLED, back.status());
    assertEquals(VisitStatus.SKIPPED, back.visits().get(1).status());
    assertEquals(record, back);
  }

  /** A line written by an older build has neither field and must still deserialise. */
  @Test
  void readsALineWithNoStatusFields() throws Exception {
    ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    String legacy = """
            {"runId":"abc","jobId":"nightly","trigger":"schedule",
             "startedAt":"2026-08-12T18:00:00Z","finishedAt":"2026-08-12T18:30:00Z",
             "visits":[{"serverId":"survival","accountId":"main",
               "startedAt":"2026-08-12T18:00:01Z","duration":"PT5M","success":false,
               "detail":"kicked","actionsRun":0,"attempts":1,"protocolVersion":776,
               "translated":false,"timeToReady":null,"outcome":"KICKED"}]}
            """;

    RunRecord record = mapper.readValue(legacy, RunRecord.class);

    assertEquals(RunStatus.FAILED, record.status());
    assertEquals(VisitStatus.FAILED, record.visits().getFirst().status());
  }
}
