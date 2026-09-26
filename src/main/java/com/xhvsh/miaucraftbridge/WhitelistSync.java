package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Two-way whitelist bridge:
 *   - mirror the server's whitelist up to Supabase (only rows that changed),
 *   - apply pending whitelist_commands rows requested from the website.
 *
 * <p>Command rows are claimed atomically (a single PATCH filtered on
 * {@code status = 'pending'}) before they are executed, so with several servers
 * (or a retrying poll) a command is applied exactly once. Usernames coming
 * from the website are validated against the Minecraft name pattern before
 * they are ever concatenated into a console command.
 */
public final class WhitelistSync {

  /** Vanilla name pattern; anything else is rejected without dispatching. */
  private static final java.util.regex.Pattern VALID_NAME =
      java.util.regex.Pattern.compile("^[A-Za-z0-9_]{1,16}$");

  private static final int MAX_COMMANDS_PER_POLL = 25;
  private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);

  private final Plugin plugin;
  private final SinkManager sinks;
  private final SupabaseRest rest;
  private final PersistedState state;
  private final Supplier<RemoteConfig> config;
  private final Logger log;

  private final JsonObject known;
  private final String instanceId;
  private volatile boolean polling = false;
  private volatile long lastMirrorMs = 0L;
  private volatile int lastPending = 0;

  public WhitelistSync(Plugin plugin, SinkManager sinks, SupabaseRest rest, PersistedState state,
      Supplier<RemoteConfig> config, Logger log) {
    this.plugin = plugin;
    this.sinks = sinks;
    this.rest = rest;
    this.state = state;
    this.config = config;
    this.log = log;
    this.known = state.object("whitelist");
    this.instanceId = UUID.randomUUID().toString();
  }

  public long lastMirrorMs() {
    return lastMirrorMs;
  }

  public int lastPending() {
    return lastPending;
  }

  /** Runs on the main thread (reads Bukkit whitelist state). */
  public void mirror() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("whitelist")) return;

    Map<String, String> current = new ConcurrentHashMap<>();
    for (OfflinePlayer op : Bukkit.getWhitelistedPlayers()) {
      if (op.getName() == null) continue;
      current.put(op.getUniqueId().toString(), op.getName());
    }

    Set<String> toDelete = new HashSet<>();
    synchronized (state) {
      for (String id : known.keySet()) {
        if (!current.containsKey(id)) toDelete.add(id);
      }
      for (Map.Entry<String, String> e : current.entrySet()) {
        JsonElement prev = known.get(e.getKey());
        if (prev == null || !prev.getAsString().equals(e.getValue())) {
          JsonObject row = new JsonObject();
          row.addProperty("id", e.getKey());
          row.addProperty("username", e.getValue());
          row.addProperty("synced_at", BridgeUtil.nowIso());
          // The diff cache is only updated once the row is really in the
          // database, so a failed flush is retried on the next mirror.
          sinks.sink("whitelist", "id", true, "id")
              .add(row, () -> remember(e.getKey(), e.getValue()));
        }
      }
    }

    for (String id : toDelete) {
      forget(id);
      // A queued upsert flushed after the delete would resurrect the row.
      sinks.discardPending("whitelist", id + "\u0000");
      rest.delete("whitelist", "whitelist", "id=eq." + id).handle((res, err) -> null);
    }
    state.save();
    lastMirrorMs = System.currentTimeMillis();
  }

  private void remember(String id, String username) {
    synchronized (state) {
      known.addProperty(id, username);
    }
  }

  private void forget(String id) {
    synchronized (state) {
      known.remove(id);
    }
  }

  /**
   * Polls pending whitelist_commands; claims and applies them off the main
   * thread. Stale 'processing' rows are polled too: a server that died
   * mid-claim would otherwise leave the request stuck forever, because the
   * claim query's stale-claim branch can only ever run for a row this poll
   * already saw.
   */
  public void pollCommands() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("whitelist") || polling) return;
    polling = true;
    String stale = SupabaseRest.encode(Instant.now().minus(CLAIM_TIMEOUT).toString());
    rest.select("whitelist-commands", "whitelist_commands",
        "select=id,action,username&or=(status.eq.pending,and(status.eq.processing,claimed_at.lt."
            + stale + "))&order=requested_at.asc&limit=" + MAX_COMMANDS_PER_POLL)
        .whenComplete((rows, err) -> {
          polling = false;
          if (err != null || rows == null || rows.isEmpty()) return;
          lastPending = rows.size();
          claimEach(rows);
        });
  }

  /**
   * Claims run on the HTTP thread - the main thread is never blocked on a
   * network call - and only the dispatch itself hops back to main.
   */
  private void claimEach(JsonArray rows) {
    java.util.concurrent.CompletableFuture<Integer> chain =
        java.util.concurrent.CompletableFuture.completedFuture(0);
    for (JsonElement el : rows) {
      chain = chain.thenCompose(applied -> {
        if (el == null || !el.isJsonObject()) return java.util.concurrent.CompletableFuture.completedFuture(applied);
        JsonObject row = el.getAsJsonObject();
        String id = str(row, "id");
        String action = str(row, "action");
        String username = str(row, "username");
        if (id == null || action == null || username == null) {
          return java.util.concurrent.CompletableFuture.completedFuture(applied);
        }
        return claim(id).thenCompose(won -> {
          if (!won) return java.util.concurrent.CompletableFuture.completedFuture(applied);
          if (!VALID_NAME.matcher(username).matches()) {
            log.warning("Rejected whitelist command for invalid username: " + username);
            finish(id, false, "invalid username");
            return java.util.concurrent.CompletableFuture.completedFuture(applied);
          }
          if (!"add".equals(action) && !"remove".equals(action)) {
            log.warning("Rejected whitelist command with unknown action: " + action);
            finish(id, false, "unknown action");
            return java.util.concurrent.CompletableFuture.completedFuture(applied);
          }
          Bukkit.getScheduler().runTask(plugin, () -> {
            String command = ("remove".equals(action) ? "whitelist remove " : "whitelist add ") + username;
            boolean ok;
            try {
              ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (Exception e) {
              ok = false;
              log.warning("Whitelist command '" + command + "' threw: " + e.getMessage());
            }
            finish(id, ok, ok ? null : "command returned false");
            if (ok) mirror();
          });
          return java.util.concurrent.CompletableFuture.completedFuture(applied + 1);
        });
      });
    }
  }

  /**
   * Atomically moves a row to 'processing'. Returns true when this instance is
   * the one that won the claim (i.e. PostgREST returned the updated row).
   */
  private java.util.concurrent.CompletableFuture<Boolean> claim(String id) {
    JsonObject patch = new JsonObject();
    patch.addProperty("status", "processing");
    patch.addProperty("claimed_at", BridgeUtil.nowIso());
    patch.addProperty("claimed_by", instanceId);
    String stale = SupabaseRest.encode(Instant.now().minus(CLAIM_TIMEOUT).toString());
    String query = "id=eq." + SupabaseRest.encode(id)
        + "&or=(status.eq.pending,and(status.eq.processing,claimed_at.lt." + stale + "))";
    return rest.raw("whitelist_commands", "PATCH", query, patch, "return=representation")
        .thenApply(res -> {
          if (!res.ok()) {
            log.warning("Could not claim whitelist command " + id + ": "
                + (res.error != null ? res.error : "HTTP " + res.code));
            return false;
          }
          if (res.body == null || res.body.isBlank()) return false;
          try {
            JsonElement parsed = JsonParser.parseString(res.body);
            return parsed.isJsonArray() && !parsed.getAsJsonArray().isEmpty();
          } catch (RuntimeException e) {
            return false;
          }
        })
        .exceptionally(err -> false);
  }

  private void finish(String id, boolean ok, String error) {
    JsonObject patch = new JsonObject();
    patch.addProperty("status", ok ? "done" : "failed");
    patch.addProperty("processed_at", BridgeUtil.nowIso());
    if (error != null) patch.addProperty("error", error);
    rest.update("whitelist-commands", "whitelist_commands", "id=eq." + id, patch)
        .handle((res, err) -> null);
  }

  private static String str(JsonObject row, String key) {
    JsonElement el = row.get(key);
    return el == null || el.isJsonNull() ? null : el.getAsString();
  }
}
