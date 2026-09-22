package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Coalescing write queues, one per table. Rows are keyed by their conflict
 * columns so a stream of updates for the same player/stat is reduced to a
 * single latest-value row before being flushed.
 *
 * Flush order across tables is fixed so foreign keys land in the right order:
 * players must exist before live_positions / player_stats / player_achievements.
 */
public class SinkManager {

  private static final List<String> FLUSH_ORDER = List.of(
      "players",
      "live_positions",
      "player_achievements",
      "player_stats",
      "achievements",
      "achievement_criteria",
      "player_achievement_criteria",
      "chat_messages",
      "whitelist",
      "server_status_public",
      "server_tps_samples",
      "biomes");

  private final SupabaseRest rest;
  private final int maxBatch;
  private final Logger log;
  private final Map<String, BatchSink> sinks = new ConcurrentHashMap<>();

  private volatile BooleanSupplier enabled;

  public SinkManager(SupabaseRest rest, int maxBatch, Logger log) {
    this.rest = rest;
    this.maxBatch = maxBatch;
    this.log = log;
    this.enabled = () -> true;
  }

  /** Global kill switch consulted before every flush (tied to config.enabled). */
  public void setEnabled(BooleanSupplier enabledSupplier) {
    this.enabled = enabledSupplier;
  }

  /**
   * @param dedupeRows if true, rows are coalesced by conflict columns; if
   *                   false (append-only tables like chat) every row is kept.
   */
  public BatchSink sink(String table, String onConflict, boolean dedupeRows, String... conflictColumns) {
    return sinks.computeIfAbsent(table, k -> new BatchSink(rest, table, onConflict, dedupeRows, conflictColumns, maxBatch, log));
  }

  /** Flush everything in FK order, chaining sequentially. Best called off the main thread. */
  public CompletableFuture<Void> flushAll() {
    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
    if (!enabled.getAsBoolean()) return chain;
    for (String table : FLUSH_ORDER) {
      BatchSink sink = sinks.get(table);
      if (sink != null) chain = chain.thenCompose(v -> sink.flush());
    }
    return chain;
  }

  public void resetCoalescing() {
    sinks.values().forEach(BatchSink::resetBatches);
  }

  public int pending(String table) {
    BatchSink sink = sinks.get(table);
    return sink == null ? 0 : sink.size();
  }

  public String lastFailure(String table) {
    return rest.lastFailure("sink:" + table);
  }

  public List<JsonObject> pendingRows(String table) {
    BatchSink sink = sinks.get(table);
    return sink == null ? List.of() : sink.copyPending();
  }

  public static final class BatchSink {
    private final SupabaseRest rest;
    private final String table;
    private final String onConflict;
    private final boolean dedupeRows;
    private final String[] conflictColumns;
    private final int maxBatch;
    private final Logger log;
    private final Map<String, JsonObject> pending = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private volatile boolean flushing = false;

    BatchSink(SupabaseRest rest, String table, String onConflict, boolean dedupeRows,
        String[] conflictColumns, int maxBatch, Logger log) {
      this.rest = rest;
      this.table = table;
      this.onConflict = onConflict;
      this.dedupeRows = dedupeRows;
      this.conflictColumns = conflictColumns;
      this.maxBatch = maxBatch;
      this.log = log;
    }

    public String table() {
      return table;
    }

    public String sinkName() {
      return "sink:" + table;
    }

    public synchronized boolean add(JsonObject row) {
      String key = dedupeRows ? keyOf(row) : Long.toString(seq.incrementAndGet());
      pending.put(key, row);
      return pending.size() >= maxBatch;
    }

    public synchronized boolean isDirty() {
      return !pending.isEmpty();
    }

    public synchronized int size() {
      return pending.size();
    }

    public synchronized List<JsonObject> copyPending() {
      return new ArrayList<>(pending.values());
    }

    public synchronized void clearRows() {
      pending.clear();
    }

    void resetBatches() {
      // kept for symmetry; coalescing maps are already re-readable
    }

    private String keyOf(JsonObject row) {
      StringBuilder sb = new StringBuilder();
      for (String col : conflictColumns) {
        sb.append(row.get(col));
        sb.append('\u0000');
      }
      return sb.toString();
    }

    private synchronized JsonArray take() {
      JsonArray batch = new JsonArray();
      for (JsonObject row : pending.values()) batch.add(row);
      pending.clear();
      return batch;
    }

    private synchronized void restore(JsonArray batch) {
      for (var el : batch) {
        JsonObject row = el.getAsJsonObject();
        String key = dedupeRows ? keyOf(row) : Long.toString(seq.incrementAndGet());
        pending.putIfAbsent(key, row);
      }
    }

    /** Flush once. Never blocks the caller's thread beyond spot-checks. */
    public synchronized CompletableFuture<Void> flush() {
      if (flushing || pending.isEmpty()) return CompletableFuture.completedFuture(null);
      if (rest.isBackedOff(sinkName())) return CompletableFuture.completedFuture(null);
      flushing = true;
      JsonArray batch = take();
      CompletableFuture<com.google.gson.JsonElement> op = dedupeRows
          ? rest.upsert(sinkName(), table, batch, onConflict)
          : rest.insert(sinkName(), table, batch);
      return op.handle((res, err) -> {
        if (err != null) {
          if (log.isLoggable(java.util.logging.Level.FINE)) {
            log.fine(table + " flush kept " + batch.size() + " row(s) for retry");
          }
          restore(batch);
        }
        flushing = false;
        return null;
      });
    }
  }
}