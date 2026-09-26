package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coalescing write queues, one per table. Rows are keyed by their conflict
 * columns so a stream of updates for the same player/stat is reduced to a
 * single latest-value row before being flushed.
 *
 * Flush order across tables is fixed so foreign keys land in the right order:
 * players and the achievement catalog must exist before live_positions,
 * player_achievements, player_achievement_criteria and player_stats
 * (see dev/prod-schema.sql: player_* tables reference players(id) and
 * achievements(key)).
 */
public class SinkManager {

  private static final List<String> FLUSH_ORDER = List.of(
      "players",
      "achievements",
      "achievement_criteria",
      "live_positions",
      "player_achievements",
      "player_achievement_criteria",
      "player_stats",
      "chat_messages",
      "whitelist",
      "server_status_public",
      "server_tps_samples");

  private final SupabaseRest rest;
  private final int maxBatch;
  private final int maxQueue;
  private final Logger log;
  private final Map<String, BatchSink> sinks = new ConcurrentHashMap<>();

  private volatile BooleanSupplier enabled;

  public SinkManager(SupabaseRest rest, int maxBatch, Logger log) {
    this.rest = rest;
    this.maxBatch = Math.max(1, maxBatch);
    // Hard memory bound so a long Supabase outage cannot grow the heap forever.
    this.maxQueue = Math.max(1000, this.maxBatch * 50);
    this.log = log;
    this.enabled = () -> true;
  }

  /** Global kill switch consulted before queue admission and every flush (tied to config.enabled). */
  public void setEnabled(BooleanSupplier enabledSupplier) {
    this.enabled = enabledSupplier == null ? () -> true : enabledSupplier;
  }

  public boolean isEnabled() {
    return enabled.getAsBoolean();
  }

  /**
   * @param dedupeRows if true, rows are coalesced by conflict columns; if
   *                   false (append-only tables like chat) every row is kept.
   */
  public BatchSink sink(String table, String onConflict, boolean dedupeRows, String... conflictColumns) {
    // Sinks read the manager's gate through this method, so a later
    // setEnabled() call is honoured by already-created queues.
    return sinks.computeIfAbsent(table, k -> new BatchSink(
        rest, table, onConflict, dedupeRows, conflictColumns, maxBatch, maxQueue, log, this::isEnabled));
  }

  /**
   * Flush everything in FK order, chaining sequentially. A sink failure does
   * not stop the remaining tables (rows are restored for the next cycle) but
   * is reported to whoever awaits the returned future.
   */
  public CompletableFuture<Void> flushAll() {
    if (!isEnabled()) return CompletableFuture.completedFuture(null);
    AtomicReference<Throwable> firstError = new AtomicReference<>();
    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
    for (String table : FLUSH_ORDER) {
      BatchSink sink = sinks.get(table);
      if (sink == null) continue;
      chain = chain.thenCompose(v -> sink.flush().handle((ignored, err) -> {
        if (err != null) firstError.compareAndSet(null, err);
        return null;
      }));
    }
    // Anything registered outside FLUSH_ORDER (e.g. a table added later) still
    // gets a chance, so no queue silently starves.
    for (Map.Entry<String, BatchSink> e : sinks.entrySet()) {
      if (FLUSH_ORDER.contains(e.getKey())) continue;
      chain = chain.thenCompose(v -> e.getValue().flush().handle((ignored, err) -> {
        if (err != null) firstError.compareAndSet(null, err);
        return null;
      }));
    }
    return chain.thenApply(v -> {
      Throwable err = firstError.get();
      if (err != null) throw new java.util.concurrent.CompletionException(err);
      return null;
    });
  }

  public void resetCoalescing() {
    sinks.values().forEach(BatchSink::resetBatches);
  }

  /**
   * Flushes one table on its own, without waiting for the shared cycle and
   * without disturbing the other tables' batching.
   *
   * <p>Only for a table that is latency-sensitive and low-volume (chat), and
   * that no other table depends on: the full cycle still runs on its own timer
   * and picks up whatever this misses. An empty queue completes immediately
   * without issuing a request, so an idle table costs nothing.
   */
  public CompletableFuture<Void> flushTable(String table) {
    BatchSink sink = sinks.get(table);
    return sink == null ? CompletableFuture.completedFuture(null) : sink.flush();
  }

  public int pending(String table) {
    BatchSink sink = sinks.get(table);
    return sink == null ? 0 : sink.size();
  }

  public int droppedRows(String table) {
    BatchSink sink = sinks.get(table);
    return sink == null ? 0 : sink.droppedRows();
  }

  public String lastFailure(String table) {
    return rest.lastFailure("sink:" + table);
  }

  public List<JsonObject> pendingRows(String table) {
    BatchSink sink = sinks.get(table);
    return sink == null ? List.of() : sink.copyPending();
  }

  /**
   * Drops queued rows whose dedupe key starts with {@code keyPrefix} (for
   * example "player_id=&lt;uuid&gt;" keys) so a queued write can never resurrect
   * a row that a delete is about to remove.
   */
  public int discardPending(String table, String keyPrefix) {
    BatchSink sink = sinks.get(table);
    return sink == null ? 0 : sink.dropPrefix(keyPrefix);
  }

  public static final class BatchSink {
    private final SupabaseRest rest;
    private final String table;
    private final String onConflict;
    private final boolean dedupeRows;
    private final String[] conflictColumns;
    private final int maxBatch;
    private final int maxQueue;
    private final Logger log;
    private final BooleanSupplier enabled;

    private final Object lock = new Object();
    private final Map<String, JsonObject> pending = new LinkedHashMap<>();
    private final Map<String, List<Runnable>> callbacks = new LinkedHashMap<>();
    private final Deque<String> order = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private final AtomicLong dropped = new AtomicLong();
    private volatile CompletableFuture<Void> inFlight = CompletableFuture.completedFuture(null);

    BatchSink(SupabaseRest rest, String table, String onConflict, boolean dedupeRows,
        String[] conflictColumns, int maxBatch, int maxQueue, Logger log, BooleanSupplier enabled) {
      this.rest = rest;
      this.table = table;
      this.onConflict = onConflict;
      this.dedupeRows = dedupeRows;
      this.conflictColumns = conflictColumns;
      this.maxBatch = maxBatch;
      this.maxQueue = maxQueue;
      this.log = log;
      this.enabled = enabled;
    }

    public String table() {
      return table;
    }

    public String sinkName() {
      return "sink:" + table;
    }

    public boolean add(JsonObject row) {
      return add(row, null);
    }

    /**
     * @param onDelivered run once the row has been accepted by the database;
     *                    used to commit diff caches only after real delivery
     */
    public boolean add(JsonObject row, Runnable onDelivered) {
      if (!enabled.getAsBoolean()) return false;
      String key = dedupeRows ? keyOf(row) : Long.toString(seq.incrementAndGet());
      synchronized (lock) {
        boolean fresh = !pending.containsKey(key);
        pending.put(key, row);
        if (fresh) order.addLast(key);
        if (onDelivered != null) {
          callbacks.computeIfAbsent(key, k -> new ArrayList<>()).add(onDelivered);
        }
        trimLocked();
      }
      return size() >= maxBatch;
    }

    /** Drops a queued row (and its callbacks) so a later delete cannot be undone by a stale write. */
    public void drop(String dedupeKey) {
      synchronized (lock) {
        pending.remove(dedupeKey);
        callbacks.remove(dedupeKey);
        order.remove(dedupeKey);
      }
    }

    public int dropPrefix(String keyPrefix) {
      int removed = 0;
      synchronized (lock) {
        for (String key : new ArrayList<>(order)) {
          if (!key.startsWith(keyPrefix)) continue;
          pending.remove(key);
          callbacks.remove(key);
          order.remove(key);
          removed++;
        }
      }
      if (removed > 0) {
        log.fine(table + ": dropped " + removed + " stale pending row(s) matching " + keyPrefix);
      }
      return removed;
    }

    private void trimLocked() {
      int excess = pending.size() - maxQueue;
      if (excess <= 0) return;
      int removed = 0;
      while (excess-- > 0 && !order.isEmpty()) {
        String oldest = order.pollFirst();
        pending.remove(oldest);
        callbacks.remove(oldest);
        dropped.incrementAndGet();
        removed++;
      }
      log.log(Level.WARNING, table + ": queue exceeded " + maxQueue + " rows, dropped the oldest "
          + removed + " row(s) - delivery cannot keep up");
    }

    public boolean isDirty() {
      return size() > 0;
    }

    public int size() {
      synchronized (lock) {
        return pending.size();
      }
    }

    public int droppedRows() {
      return (int) dropped.get();
    }

    public List<JsonObject> copyPending() {
      synchronized (lock) {
        return new ArrayList<>(pending.values());
      }
    }

    public void clearRows() {
      synchronized (lock) {
        pending.clear();
        callbacks.clear();
        order.clear();
      }
    }

    void resetBatches() {
      // kept for symmetry; coalescing maps are already re-readable
    }

    private String keyOf(JsonObject row) {
      StringBuilder sb = new StringBuilder();
      for (String col : conflictColumns) {
        JsonElement value = row.get(col);
        sb.append(value == null || value.isJsonNull() ? "" : value.getAsString()).append('\u0000');
      }
      return sb.toString();
    }

    /** One queued row plus the callbacks that must fire once it is delivered. */
    private record Item(String key, JsonObject row, List<Runnable> callbacks) {
    }

    private record Chunk(List<Item> items) {
      JsonArray rows() {
        JsonArray arr = new JsonArray();
        for (Item item : items) arr.add(item.row);
        return arr;
      }

      int size() {
        return items.size();
      }
    }

    /** Takes the oldest queued rows (FIFO) so a busy table cannot starve its own history. */
    private Chunk takeBatch() {
      synchronized (lock) {
        List<Item> items = new ArrayList<>(Math.min(maxBatch, pending.size()));
        while (items.size() < maxBatch && !order.isEmpty()) {
          String key = order.pollFirst();
          JsonObject row = pending.remove(key);
          if (row == null) continue;
          items.add(new Item(key, row, callbacks.remove(key)));
        }
        return new Chunk(items);
      }
    }

    private void restore(Chunk chunk) {
      synchronized (lock) {
        for (Item item : chunk.items()) {
          JsonObject current = pending.get(item.key());
          if (current == null) {
            pending.put(item.key(), item.row());
            order.addLast(item.key());
          }
          if (item.callbacks() == null || item.callbacks().isEmpty()) continue;
          List<Runnable> queued = callbacks.get(item.key());
          if (queued == null) {
            callbacks.put(item.key(), new ArrayList<>(item.callbacks()));
          } else {
            // A newer row for the same key is already queued, so this chunk's
            // callbacks are the older ones and must run first - otherwise the
            // delivery cache would be committed with a stale value last.
            queued.addAll(0, item.callbacks());
          }
        }
      }
    }

    /**
     * Flush once, in chunks of at most {@code maxBatch} rows. Returns the
     * in-flight future when a flush is already running so callers (notably the
     * shutdown drain) always observe the real outcome instead of a fake
     * "already done".
     */
    public CompletableFuture<Void> flush() {
      if (!enabled.getAsBoolean()) return CompletableFuture.completedFuture(null);
      if (!flushing.compareAndSet(false, true)) {
        CompletableFuture<Void> current = inFlight;
        return current == null ? CompletableFuture.completedFuture(null) : current;
      }
      CompletableFuture<Void> done = new CompletableFuture<>();
      inFlight = done;
      pump(done);
      return done;
    }

    private void pump(CompletableFuture<Void> done) {
      if (rest.isBackedOff(sinkName())) {
        flushing.set(false);
        inFlight = CompletableFuture.completedFuture(null);
        done.completeExceptionally(new SupabaseRest.HttpError(
            table + " flush skipped (backoff after " + rest.failures(sinkName()) + " failures)", true));
        return;
      }
      Chunk chunk = takeBatch();
      if (chunk.size() == 0) {
        flushing.set(false);
        inFlight = CompletableFuture.completedFuture(null);
        done.complete(null);
        return;
      }
      CompletableFuture<JsonElement> op = dedupeRows
          ? rest.upsert(sinkName(), table, chunk.rows(), onConflict)
          : rest.insert(sinkName(), table, chunk.rows());
      op.whenComplete((res, err) -> {
        if (err != null) {
          if (log.isLoggable(Level.FINE)) {
            log.fine(table + ": kept " + chunk.size() + " row(s) for retry after " + err.getMessage());
          }
          restore(chunk);
          flushing.set(false);
          inFlight = CompletableFuture.completedFuture(null);
          done.completeExceptionally(err);
        } else {
          for (Item item : chunk.items()) {
            if (item.callbacks() == null) continue;
            for (Runnable cb : item.callbacks()) {
              try {
                cb.run();
              } catch (RuntimeException e) {
                log.warning(table + ": delivery callback failed: " + e.getMessage());
              }
            }
          }
          pump(done);
        }
      });
    }
  }
}
