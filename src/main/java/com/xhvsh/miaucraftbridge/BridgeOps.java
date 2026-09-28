package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Owner-facing operations: publishes this plugin's own state to the website,
 * mirrors its console log so new features can be tested from a browser, and
 * runs a small fixed set of maintenance actions requested by an owner.
 *
 * Three design points that are not obvious from the code:
 *
 *   * The console mirror is rate limited per distinct message. A dead database
 *     makes every flush log a failure, and capturing those rows naively would
 *     turn one outage into a flood of its own error messages. Collapsing
 *     repeats to one row per window keeps the useful first failure and drops
 *     the echo.
 *
 *   * The status row is a single coalescing upsert, so the website always reads
 *     the newest snapshot rather than a backlog of stale ones.
 *
 *   * Command results are captured with an idle watcher rather than a
 *     synchronous read, because most of these actions report their outcome from
 *     a later callback.
 */
public final class BridgeOps {

  private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);
  private static final int MAX_COMMANDS_PER_POLL = 5;

  private static final long CONSOLE_WINDOW_MS = 60_000L;
  private static final int CONSOLE_KEYS_MAX = 500;
  private static final int CONSOLE_ROWS_PER_WINDOW_MAX = 240;

  /** How long a command may stay silent after its first line before we finalise. */
  private static final long IDLE_FINISH_MS = 1_500L;
  private static final long COMMAND_TIMEOUT_MS = 90_000L;
  private static final int WATCH_TICKS = 10;

  /**
   * The fixed set of actions the website may request. This is the complete
   * list: there is no path from a database row to a console command string, so
   * a row can only ever name one of these symbols. Kept in sync with the CHECK
   * constraint in the bridge operations migration, which a test enforces.
   */
  static final List<String> COMMANDS = List.of(
      "update.check",
      "update.apply",
      "config.reload",
      "connection.test",
      "stats.reconcile",
      "sinks.drain",
      "sinks.flush");

  /** Tables reported on the Status tab. Shared with the in-game flush command. */
  static final Set<String> TRACKED_SINKS = new LinkedHashSet<>(List.of(
      "players", "player_stats", "player_achievements", "player_achievement_criteria",
      "achievements", "achievement_criteria", "live_positions", "chat_messages",
      "whitelist", "server_status_public", "server_tps_samples",
      "bridge_events", "bridge_console"));

  private final MiaucraftBridgePlugin plugin;
  private final SupabaseRest rest;
  private final SinkManager sinks;
  private final String instanceId = UUID.randomUUID().toString();
  private final Instant startedAt = Instant.now();

  private String pluginVersion = "unknown";
  private String serverVersion = "unknown";
  private volatile boolean polling;
  private boolean online = true;
  private int summarizeEvery;

  private final List<BukkitTask> watchers = new ArrayList<>();

  public BridgeOps(MiaucraftBridgePlugin plugin) {
    this.plugin = plugin;
    this.rest = plugin.restApi();
    this.sinks = plugin.sinkManager();
  }

  // ------------------------------------------------------------------- setup

  /**
   * Captures the plugin's own logger into bridge_console. The handler level is
   * ALL so it records exactly what the server would print, and the plugin
   * logger's own level still decides what is published.
   */
  public void start() {
    pluginVersion = plugin.getPluginMeta().getVersion();
    serverVersion = Bukkit.getServer().getBukkitVersion();
    plugin.getLogger().addHandler(new ConsoleMirror());
  }

  public void stop() {
    for (BukkitTask t : watchers) {
      try {
        t.cancel();
      } catch (Exception ignored) {
      }
    }
    watchers.clear();
  }

  public String instanceId() {
    return instanceId;
  }

  // ------------------------------------------------------------------ events

  public void report(String level, String category, String event, String message, JsonObject details) {
    if (!online) return;
    try {
      JsonObject row = new JsonObject();
      row.addProperty("created_at", BridgeUtil.nowIso());
      row.addProperty("level", level);
      row.addProperty("category", category);
      row.addProperty("event", event);
      row.addProperty("message", message);
      row.add("details", details == null ? new JsonObject() : details);
      row.addProperty("instance_id", instanceId);
      row.addProperty("plugin_version", pluginVersion);
      sinks.sink("bridge_events", "id", false, "id").add(row);
    } catch (RuntimeException ignored) {
      // Never let diagnostics be the reason a collector fails.
    }
  }

  public void info(String category, String event, String message) {
    report("info", category, event, message, null);
  }

  public void warn(String category, String event, String message) {
    report("warn", category, event, message, null);
  }

  public void error(String category, String event, String message) {
    report("error", category, event, message, null);
  }

  public void setOnline(boolean value) {
    this.online = value;
  }

  // ------------------------------------------------------------------ status

  /**
   * Publishes the snapshot the website's Status tab reads. Safe to call from
   * the async task: it only reads counters and queue depths.
   */
  public void writeStatus() {
    if (!online) return;
    try {
      sinks.sink("bridge_status", "id", true, "id").add(statusRow(true));
    } catch (RuntimeException ignored) {
    }
  }

  /**
   * Records the shutdown. The row is written after the online flag is cleared so
   * the website shows the server as offline even though nothing runs to update
   * it again.
   */
  public void markOffline() {
    info("lifecycle", "server.offline", "Plugin disabled");
    online = false;
    try {
      sinks.sink("bridge_status", "id", true, "id").add(statusRow(false));
    } catch (RuntimeException ignored) {
    }
  }

  private JsonObject statusRow(boolean isOnline) {
    JsonObject row = new JsonObject();
    row.addProperty("id", 1);
    row.addProperty("instance_id", instanceId);
    row.addProperty("plugin_version", pluginVersion);
    row.addProperty("server_version", serverVersion);
    row.addProperty("online", isOnline);
    row.addProperty("started_at", startedAt.toString());
    row.addProperty("updated_at", BridgeUtil.nowIso());
    RemoteConfig cfg = plugin.config();
    row.addProperty("remote_config_version", cfg == null ? 0 : cfg.version());
    row.add("sinks", sinkSnapshot());
    row.add("counters", counters());
    return row;
  }

  private JsonObject sinkSnapshot() {
    JsonObject out = new JsonObject();
    for (String table : TRACKED_SINKS) {
      JsonObject one = new JsonObject();
      one.addProperty("pending", sinks.pending(table));
      int dropped = sinks.droppedRows(table);
      if (dropped > 0) one.addProperty("dropped", dropped);
      String err = sinks.lastFailure(table);
      if (err != null && !err.isBlank()) one.addProperty("last_error", err);
      out.add(table, one);
    }
    return out;
  }

  private JsonObject counters() {
    JsonObject out = new JsonObject();
    try {
      out.addProperty("chat_relayed", plugin.chatBridge() == null ? 0 : plugin.chatBridge().lastRelayed());
      out.addProperty("whitelist_mirror_ms_ago", agoMs(plugin.whitelistSync() == null ? 0 : plugin.whitelistSync().lastMirrorMs()));
      out.addProperty("achievement_scan_ms_ago", agoMs(plugin.achievementCollector() == null ? 0 : plugin.achievementCollector().lastScanMs()));
      out.addProperty("stats_reconcile_running", plugin.statCollector() != null && plugin.statCollector().isRunning());
      if (plugin.statCollector() != null) {
        out.addProperty("stats_last_players", plugin.statCollector().lastPlayers());
        out.addProperty("stats_last_changed", plugin.statCollector().lastChanged());
      }
      out.addProperty("console_suppressed", consoleWindow.suppressed());
    } catch (RuntimeException ignored) {
    }
    return out;
  }

  private static long agoMs(long lastMs) {
    return lastMs <= 0 ? -1 : System.currentTimeMillis() - lastMs;
  }

  /**
   * One rolled-up line per interval rather than a row per player or per write.
   * Runs on the main thread because it reads the online player count.
   */
  public void summarize() {
    if (!online) return;
    try {
      JsonObject d = new JsonObject();
      d.addProperty("players_online", Bukkit.getServer().getOnlinePlayers().size());
      ChatBridge chat = plugin.chatBridge();
      d.addProperty("chat_relayed", chat == null ? 0 : chat.lastRelayed());
      StatCollector stats = plugin.statCollector();
      if (stats != null) {
        d.addProperty("stat_reconcile_players", stats.lastPlayers());
        d.addProperty("stat_values_changed", stats.lastChanged());
      }
      AchievementCollector ach = plugin.achievementCollector();
      d.addProperty("achievement_scan_ms_ago", agoMs(ach == null ? 0 : ach.lastScanMs()));
      WhitelistSync wl = plugin.whitelistSync();
      d.addProperty("whitelist_mirror_ms_ago", agoMs(wl == null ? 0 : wl.lastMirrorMs()));

      int queued = 0;
      JsonObject failing = new JsonObject();
      for (String table : TRACKED_SINKS) {
        queued += sinks.pending(table);
        String err = sinks.lastFailure(table);
        if (err != null && !err.isBlank()) failing.addProperty(table, err);
      }
      d.addProperty("rows_queued", queued);
      if (failing.size() > 0) d.add("failing_sinks", failing);

      summarizeEvery++;
      d.addProperty("interval", summarizeEvery);

      if (failing.size() > 0) {
        report("warn", "collector", "collector.summary",
            summarizeEvery + " queued row(s), " + failing.size() + " sink(s) failing", d);
      } else {
        report("info", "collector", "collector.summary",
            summarizeEvery + " queued row(s), all sinks healthy", d);
      }
    } catch (RuntimeException ignored) {
    }
  }

  // ----------------------------------------------------------------- console

  private final class ConsoleMirror extends Handler {

    @Override
    public void publish(LogRecord record) {
      if (record == null || !isLoggable(record)) return;
      try {
        String message = record.getMessage();
        if (message == null) return;
        Throwable thrown = record.getThrown();
        if (thrown != null) {
          message = message + " - " + thrown;
        }
        if (!shouldCapture(record.getLevel(), message)) return;

        JsonObject row = new JsonObject();
        row.addProperty("created_at", BridgeUtil.nowIso());
        row.addProperty("level", levelName(record.getLevel()));
        row.addProperty("logger", record.getLoggerName());
        row.addProperty("message", CapturingSender.stripColors(message));
        if (thrown != null) {
          row.addProperty("throwable", CapturingSender.stripColors(thrown.toString()));
        }
        row.addProperty("instance_id", instanceId);
        row.addProperty("plugin_version", pluginVersion);
        sinks.sink("bridge_console", "id", false, "id").add(row);
      } catch (RuntimeException ignored) {
        // A failure to record a log line must never be logged again.
      }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
  }

  /**
   * One row per distinct message per window. Repeats of the same failure are
   * counted, not stored.
   */
  private final RateWindow consoleWindow =
      new RateWindow(CONSOLE_WINDOW_MS, CONSOLE_KEYS_MAX, CONSOLE_ROWS_PER_WINDOW_MAX);

  private boolean shouldCapture(Level level, String message) {
    return consoleWindow.accept(levelName(level) + '|' + message);
  }

  private static String levelName(Level level) {
    if (level == null) return "info";
    if (level.intValue() >= Level.SEVERE.intValue()) return "error";
    if (level.intValue() >= Level.WARNING.intValue()) return "warn";
    return "info";
  }

  // ---------------------------------------------------------------- commands

  /**
   * Claims pending bridge_commands and runs them on the main thread.
   *
   * The action is a fixed symbol mapped to a method below - there is no path
   * from a database row to a console command string, so a row cannot be used
   * to run an arbitrary command.
   */
  public void pollCommands() {
    if (polling || !online) return;
    polling = true;
    String stale = SupabaseRest.encode(Instant.now().minus(CLAIM_TIMEOUT).toString());
    rest.select("bridge-commands", "bridge_commands",
            "select=id,command,args,requested_by_username&or=(status.eq.pending,and(status.eq.processing,claimed_at.lt."
                + stale + "))&order=requested_at.asc&limit=" + MAX_COMMANDS_PER_POLL)
        .whenComplete((rows, err) -> {
          polling = false;
          if (err != null || rows == null || rows.isEmpty()) return;
          claimEach(rows);
        });
  }

  private void claimEach(JsonArray rows) {
    CompletableFuture<Integer> chain = CompletableFuture.completedFuture(0);
    for (JsonElement el : rows) {
      chain = chain.thenCompose(applied -> {
        if (el == null || !el.isJsonObject()) {
          return CompletableFuture.completedFuture(applied);
        }
        JsonObject row = el.getAsJsonObject();
        String id = str(row, "id");
        String command = str(row, "command");
        if (id == null || command == null) {
          return CompletableFuture.completedFuture(applied);
        }
        return claim(id).thenCompose(won -> {
          if (!won) return CompletableFuture.completedFuture(applied);
          dispatch(id, command, row.has("args") ? row.get("args") : null);
          return CompletableFuture.completedFuture(applied + 1);
        });
      });
    }
  }

  private CompletableFuture<Boolean> claim(String id) {
    JsonObject patch = new JsonObject();
    patch.addProperty("status", "processing");
    patch.addProperty("claimed_at", BridgeUtil.nowIso());
    patch.addProperty("claimed_by", instanceId);
    String stale = SupabaseRest.encode(Instant.now().minus(CLAIM_TIMEOUT).toString());
    String query = "id=eq." + SupabaseRest.encode(id)
        + "&or=(status.eq.pending,and(status.eq.processing,claimed_at.lt." + stale + "))";
    return rest.raw("bridge_commands", "PATCH", query, patch, "return=representation")
        .thenApply(res -> {
          if (!res.ok()) {
            logClaimFailure(id, res.error != null ? res.error : "HTTP " + res.code);
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

  private void logClaimFailure(String id, String reason) {
    warn("command", "command.claim_failed", "Could not claim command " + id + ": " + reason);
  }

  /**
   * Runs the action on the main thread behind a capturing sender, then watches
   * the sender until it goes quiet so an asynchronous result is still reported.
   */
  private void dispatch(String id, String command, JsonElement args) {
    Bukkit.getScheduler().runTask(plugin, () -> {
      CapturingSender sender = new CapturingSender(Bukkit.getConsoleSender());

      try {
        switch (command) {
          case "update.check" -> plugin.checkUpdate(sender);
          case "update.apply" -> plugin.applyUpdate(sender);
          case "config.reload" -> plugin.reloadRemote(sender);
          case "connection.test" -> plugin.testConnection(sender);
          case "stats.reconcile" -> plugin.forceStats(sender);
          case "sinks.drain" -> plugin.drainAchievements(sender);
          case "sinks.flush" -> plugin.flushSinks(sender);
          default -> {
            finish(id, false, "unknown command", null);
            return;
          }
        }
      } catch (RuntimeException e) {
        finish(id, false, "threw: " + e.getMessage(), null);
        return;
      }

      report("info", "command", "command.started", "Started " + command, null);
      watch(id, command, sender);
    });
  }

  private void watch(String id, String command, CapturingSender sender) {
    long[] lastLineCount = {sender.lines().size()};
    long[] lastChange = {System.currentTimeMillis()};
    long started = lastChange[0];

    final BukkitTask[] holder = new BukkitTask[1];
    holder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
      int size = sender.lines().size();
      if (size != lastLineCount[0]) {
        lastLineCount[0] = size;
        lastChange[0] = System.currentTimeMillis();
      }
      long idle = System.currentTimeMillis() - lastChange[0];
      long elapsed = System.currentTimeMillis() - started;

      boolean timedOut = elapsed >= COMMAND_TIMEOUT_MS;
      boolean settled = size > 0 && idle >= IDLE_FINISH_MS;
      if (!settled && !timedOut) return;

      holder[0].cancel();
      watchers.remove(holder[0]);
      String output = sender.text();
      boolean ok = !sender.sawError() && !output.isBlank();
      String failure = ok ? null : lastLine(sender);
      finish(id, ok, failure, output);
      report(ok ? "info" : "error", "command",
          ok ? "command.done" : "command.failed",
          command + (ok ? " completed" : " failed"), null);
    }, WATCH_TICKS, WATCH_TICKS);

    watchers.add(holder[0]);
  }

  private static String lastLine(CapturingSender sender) {
    List<String> lines = sender.lines();
    return lines.isEmpty() ? "no output" : lines.get(lines.size() - 1);
  }

  private void finish(String id, boolean ok, String error, String output) {
    JsonObject patch = new JsonObject();
    patch.addProperty("status", ok ? "done" : "failed");
    patch.addProperty("processed_at", BridgeUtil.nowIso());
    if (error != null) patch.addProperty("error", error);
    if (output != null && !output.isBlank()) patch.addProperty("result", output);
    rest.update("bridge-commands", "bridge_commands", "id=eq." + id, patch)
        .handle((res, err) -> null);
  }

  private static String str(JsonObject row, String key) {
    JsonElement el = row.get(key);
    return el == null || el.isJsonNull() ? null : el.getAsString();
  }
}
