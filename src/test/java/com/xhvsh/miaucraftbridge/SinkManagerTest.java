package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The queues are where correctness of the whole bridge is decided: coalescing,
 * retry bookkeeping, delivery ordering and the global kill switch.
 */
class SinkManagerTest {

  private static final Logger LOG = Logger.getLogger("test");

  /** Records what would have gone over the wire and can be told to fail. */
  static final class FakeRest extends SupabaseRest {
    final List<String> calls = new ArrayList<>();
    final AtomicInteger failuresLeft = new AtomicInteger();
    volatile JsonArray upsertSent;
    volatile JsonArray insertSent;

    FakeRest() {
      super("https://example.supabase.co", "service-role-key", "public", LOG);
    }

    @Override
    public CompletableFuture<JsonElement> upsert(String sink, String table, JsonArray rows,
        String onConflict) {
      synchronized (this) {
        calls.add(table);
        upsertSent = rows.deepCopy();
      }
      if (failuresLeft.getAndDecrement() > 0) {
        CompletableFuture<JsonElement> failed = new CompletableFuture<>();
        failed.completeExceptionally(new SupabaseRest.HttpError("boom", false));
        return failed;
      }
      return CompletableFuture.completedFuture(new com.google.gson.JsonObject());
    }

    @Override
    public CompletableFuture<JsonElement> insert(String sink, String table, JsonArray rows) {
      synchronized (this) {
        calls.add(table);
        insertSent = rows.deepCopy();
      }
      if (failuresLeft.getAndDecrement() > 0) {
        CompletableFuture<JsonElement> failed = new CompletableFuture<>();
        failed.completeExceptionally(new SupabaseRest.HttpError("boom", false));
        return failed;
      }
      return CompletableFuture.completedFuture(new com.google.gson.JsonObject());
    }

    @Override
    public boolean isBackedOff(String sink) {
      return false;
    }
  }

  private static JsonObject row(String playerId, String key, long value) {
    JsonObject row = new JsonObject();
    row.addProperty("player_id", playerId);
    row.addProperty("stat_key", key);
    row.addProperty("stat_value", value);
    return row;
  }

  @Test
  void sameConflictKeyCollapsesToTheLatestRow() throws Exception {
    FakeRest rest = new FakeRest();
    SinkManager sinks = new SinkManager(rest, 100, LOG);
    var sink = sinks.sink("player_stats", "player_id,stat_key", true, "player_id", "stat_key");
    sink.add(row("p1", "MINE_BLOCK:STONE", 1));
    sink.add(row("p1", "MINE_BLOCK:STONE", 7));
    sink.add(row("p1", "MINE_BLOCK:DIRT", 3));
    sink.add(row("p2", "MINE_BLOCK:STONE", 5));

    assertEquals(3, sinks.pending("player_stats"));
    sink.flush().get();

    assertEquals(1, rest.calls.size());
    JsonArray sent = rest.upsertSent;
    assertEquals(3, sent.size());
    for (JsonElement el : sent) {
      JsonObject o = el.getAsJsonObject();
      if (o.get("stat_key").getAsString().equals("MINE_BLOCK:STONE")
          && o.get("player_id").getAsString().equals("p1")) {
        assertEquals(7, o.get("stat_value").getAsLong());
      }
    }
    assertEquals(0, sinks.pending("player_stats"));
  }

  @Test
  void appendOnlyTablesKeepEveryRow() throws Exception {
    FakeRest rest = new FakeRest();
    SinkManager sinks = new SinkManager(rest, 100, LOG);
    var chat = sinks.sink("chat_messages", "id", false, "id");
    chat.add(row("p1", "a", 1));
    chat.add(row("p1", "a", 2));
    chat.add(row("p1", "a", 3));
    assertEquals(3, sinks.pending("chat_messages"));
    chat.flush().get();
    assertEquals(3, rest.insertSent.size());
  }

  @Test
  void deliveryCallbackFiresOnlyAfterTheRowIsAccepted() throws Exception {
    FakeRest rest = new FakeRest();
    SinkManager sinks = new SinkManager(rest, 100, LOG);
    var sink = sinks.sink("player_stats", "player_id,stat_key", true, "player_id", "stat_key");
    AtomicInteger committed = new AtomicInteger();
    sink.add(row("p1", "k", 1), committed::incrementAndGet);
    assertEquals(0, committed.get());
    sink.flush().get();
    assertEquals(1, committed.get());
  }

  @Test
  void aFailedFlushIsRetriedAndCommitsTheNewestValueLast() throws Exception {
    FakeRest rest = new FakeRest();
    SinkManager sinks = new SinkManager(rest, 100, LOG);
    var sink = sinks.sink("player_stats", "player_id,stat_key", true, "player_id", "stat_key");

    // Commit log stands in for the delivery-confirmed cache: the last value
    // written must be the newest one, even across a failed flush.
    List<Long> committed = new ArrayList<>();
    rest.failuresLeft.set(1);
    sink.add(row("p1", "k", 1), () -> committed.add(1L));
    sink.add(row("p1", "k", 5), () -> committed.add(5L));

    CompletableFuture<Void> first = sink.flush();
    assertTrue(first.isCompletedExceptionally(), "the first attempt is reported as failed");
    assertEquals(1, sinks.pending("player_stats"), "the row is kept for the retry");

    sink.flush().get();
    assertTrue(committed.size() >= 1);
    assertEquals(5L, committed.get(committed.size() - 1),
        "the newest value must be committed last, never overwritten by the retry");
    assertEquals(0, sinks.pending("player_stats"));
  }

  @Test
  void flushOrderRespectsForeignKeys() throws Exception {
    FakeRest rest = new FakeRest();
    SinkManager sinks = new SinkManager(rest, 100, LOG);
    // Register the child table first on purpose.
    sinks.sink("player_stats", "player_id,stat_key", true, "player_id", "stat_key").add(row("p1", "k", 1));
    JsonObject player = new JsonObject();
    player.addProperty("id", "p1");
    player.addProperty("username", "Player");
    sinks.sink("players", "id", true, "id").add(player);
    sinks.sink("live_positions", "player_id", true, "player_id").add(row("p1", "x", 1));

    sinks.flushAll().get();
    assertEquals(List.of("players", "live_positions", "player_stats"), rest.calls);
  }

  @Test
  void killSwitchBlocksAdmissionAndFlushing() throws Exception {
    FakeRest rest = new FakeRest();
    SinkManager sinks = new SinkManager(rest, 100, LOG);
    var sink = sinks.sink("player_stats", "player_id,stat_key", true, "player_id", "stat_key");
    sink.add(row("p1", "k", 1));
    assertEquals(1, sinks.pending("player_stats"));

    AtomicBoolean gate = new AtomicBoolean(false);
    sinks.setEnabled(gate::get);
    assertFalse(sink.add(row("p1", "k", 2)), "nothing is queued while the switch is off");
    sink.flush().get();
    assertTrue(rest.calls.isEmpty(), "no request is sent while the switch is off");
    assertEquals(1, sinks.pending("player_stats"), "queued rows are kept, not dropped");

    gate.set(true);
    sink.flush().get();
    assertEquals(1, rest.calls.size());
  }

  @Test
  void discardPendingRemovesRowsThatWouldResurrectADeletedPlayer() {
    FakeRest rest = new FakeRest();
    SinkManager sinks = new SinkManager(rest, 100, LOG);
    var sink = sinks.sink("live_positions", "player_id", true, "player_id");
    sink.add(row("p1", "x", 1));
    sink.add(row("p2", "x", 2));

    assertEquals(1, sinks.discardPending("live_positions", "p1\u0000"));
    assertEquals(1, sinks.pending("live_positions"));
    assertEquals("p2", sinks.pendingRows("live_positions").get(0).get("player_id").getAsString());
  }

  @Test
  void queueIsBoundedAndDropsTheOldestRows() {
    FakeRest rest = new FakeRest();
    // maxBatch 1 => bound of 1000 rows.
    SinkManager sinks = new SinkManager(rest, 1, LOG);
    var sink = sinks.sink("chat_messages", "id", false, "id");
    for (int i = 0; i < 1005; i++) {
      sink.add(row("p1", "k" + i, i));
    }
    assertEquals(1000, sinks.pending("chat_messages"));
    assertEquals(5, sinks.droppedRows("chat_messages"));
    assertEquals("k5", sinks.pendingRows("chat_messages").get(0).get("stat_key").getAsString());
  }
}
