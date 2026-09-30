package com.xhvsh.miaucraftbridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Reworked Miaucraft bridge.
 *
 * Differences from the old plugin that matter:
 *   - behavior comes from a remote GitHub config, re-applied live every poll;
 *   - writes are coalesced per table and flushed in FK order by one scheduler;
 *   - stats are event-driven + online-only reconcile, so a local test server
 *     cannot rewrite production rows and a restart does not re-flush history;
 *   - credentials live only in this server's local config.yml.
 */
public final class MiaucraftBridgePlugin extends JavaPlugin {

  private static final String DEFAULT_REMOTE_URL =
      "https://raw.githubusercontent.com/xhvsh/miaucraft-bridge/main/remote-config.json";

  private final List<BukkitTask> tasks = new ArrayList<>();
  private final Gson gson = new Gson();

  private String remoteUrl;
  private int remotePollSeconds;
  private Path remoteCachePath;
  private JsonObject overrides;
  private boolean masterEnabled;
  private String restSchema = "public";
  private String supabaseUrl;
  private String supabaseKey;

  private SupabaseRest rest;
  private SinkManager sinks;
  private PersistedState state;
  private volatile RemoteConfig remoteConfig;

  private Updater updater;
  private BukkitTask updateTask;

  private PresenceTracker presence;
  private LivePositionTracker positions;
  private StatCollector stats;
  private AchievementCollector achievements;
  private ServerStatusCollector status;
  private TpsSampler tps;
  private WhitelistSync whitelist;
  private ChatBridge chat;
  private BridgeOps ops;
  private LiveMap liveMap;

  private volatile boolean firstConfigApplied = false;

  /** Bumped per fetch so a slow/older response can never overwrite a newer one. */
  private final java.util.concurrent.atomic.AtomicLong remoteGeneration =
      new java.util.concurrent.atomic.AtomicLong();

  /** The local config.yml "enabled" kill switch, read live through this method. */
  private boolean masterEnabled() {
    return masterEnabled;
  }

  @Override
  public void onEnable() {
    saveDefaultConfig();

    String url = getConfig().getString("supabase.url", "");
    String key = getConfig().getString("supabase.service-role-key", "");
    String schema = getConfig().getString("supabase.schema", "public");
    if (url.isBlank() || key.isBlank()) {
      getLogger().severe("supabase.url / service-role-key are not set in config.yml - disabling.");
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    remoteUrl = getConfig().getString("remote.url", DEFAULT_REMOTE_URL);
    remotePollSeconds = Math.max(60, getConfig().getInt("remote.poll-seconds", 300));
    remoteCachePath = getDataFolder().toPath().resolve(
        getConfig().getString("remote.cache-file", "remote-config-last-good.json"));
    overrides = readOverrides();
    masterEnabled = getConfig().getBoolean("enabled", true);

    rest = new SupabaseRest(url, key, schema, getLogger());
    int maxBatch = Math.max(1, getConfig().getInt("max-batch-size", 300));
    sinks = new SinkManager(rest, maxBatch, getLogger());
    // One stable supplier for the whole plugin: every collector, queue and REST
    // call reads config.yml "enabled" through it, so flipping it to false is a
    // real kill switch (no new rows are queued or sent, in-flight queues stop).
    BooleanSupplier masterGate = () -> masterEnabled;
    rest.setEnabled(masterGate);
    sinks.setEnabled(masterGate);
    state = new PersistedState(getDataFolder().toPath().resolve("bridge-state.json"), getLogger());

    Supplier<RemoteConfig> cfg = () -> remoteConfig;
    remoteConfig = RemoteConfig.fromJson(null, overrides);
    remoteConfig.setMasterEnabled(masterGate);
    restSchema = schema;
    supabaseUrl = url;
    supabaseKey = key;

    chat = new ChatBridge(this, sinks, rest, state, cfg, getLogger());
    presence = new PresenceTracker(sinks, cfg, chat);
    positions = new LivePositionTracker(sinks, rest, cfg, getLogger());
    stats = new StatCollector(this, sinks, state, cfg, getLogger());
    achievements = new AchievementCollector(this, sinks, state, cfg, getLogger());
    status = new ServerStatusCollector(sinks, cfg);
    tps = new TpsSampler(sinks, cfg);
    whitelist = new WhitelistSync(this, sinks, rest, state, cfg, getLogger());
    updater = new Updater(this, remoteUrl);
    ops = new BridgeOps(this);
    ops.start();

    // The live map is toggled by the remote config (map.enabled) like every
    // other behavior, so it can be flipped from GitHub without touching any
    // server file. Until the first supported remote config lands, the local
    // config.yml "map.enabled" is the initial state.
    syncLiveMap();

    getServer().getPluginManager().registerEvents(presence, this);
    getServer().getPluginManager().registerEvents(positions, this);
    getServer().getPluginManager().registerEvents(stats, this);
    getServer().getPluginManager().registerEvents(achievements, this);
    getServer().getPluginManager().registerEvents(new Listener() {
      @EventHandler
      public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        chat.onPlayerChat(e);
      }
    }, this);

    BridgeCommand bridgeCommand = new BridgeCommand(this);
    if (getCommand("bridge") != null) getCommand("bridge").setExecutor(bridgeCommand);
    if (getCommand("refreshstats") != null) {
      getCommand("refreshstats").setExecutor((sender, command, label, args) -> {
        if (!sender.hasPermission("miaucraftbridge.admin")) {
          sender.sendMessage("§cYou don't have permission to use this.");
          return true;
        }
        forceStats(sender);
        return true;
      });
    }

    rescheduleTasks();
    scheduleUpdateChecks();

    // First tick: cover players who were already online before this plugin
    // (re)loaded, then sync the achievement catalog, then pull the remote config.
    Bukkit.getScheduler().runTask(this, () -> {
      presence.initOnline();
      positions.initOnline();
      status.report();
      achievements.syncCatalog();
    });
    pollRemote();

    getLogger().info("Enabled - remote config from " + remoteUrl);
    ops.info("lifecycle", "server.online", "Plugin enabled");
    ops.writeStatus();
  }

  @Override
  public void onDisable() {
    // Stop producing new work first, so the drain below sees a quiet queue.
    cancelTasks();
    if (updateTask != null) {
      try {
        updateTask.cancel();
      } catch (Exception ignored) {
      }
      updateTask = null;
    }
    if (ops != null) ops.stop();
    if (liveMap != null) liveMap.stop();
    if (rest == null) return;

    try {
      presence.shutdown();
      // The online-status marker must be published before the queued rows.
      chat.notice("Server offline");
      // Written before the flush so the shutdown event and the offline status
      // row go out with the rest of the final batch.
      if (ops != null) ops.markOffline();
      sinks.flushAll().get(8, TimeUnit.SECONDS);
      // ...and only then remove the live markers, so a pending upsert can never
      // be flushed after its own delete (which used to leave players "online").
      positions.shutdown().get(5, TimeUnit.SECONDS);
      state.save();
    } catch (Exception e) {
      getLogger().warning("Shutdown flush incomplete: " + e.getMessage());
    }
  }

  // ------------------------------------------------------------- scheduling

  private void rescheduleTasks() {
    cancelTasks();
    RemoteConfig cfg = remoteConfig;

    tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
      sinks.flushAll();
      state.save();
    }, 100L, 100L));

    // Advances the armed achievement scan in small main-thread slices.
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, achievements::pumpOnlineScan, 40L, 5L));

    // Arms the online progress scan. scanOnline() only flips a flag (the roster
    // is snapshotted by the pump above on the main thread), so it is safe here.
    long achProgress = Math.max(1, cfg.collectorLong("achievements", "progress-scan-seconds", 60)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, achievements::scanOnline, 600L, achProgress));

    long heartbeatPeriod = Math.max(30, cfg.collectorLong("presence", "heartbeat-seconds", 300)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, presence::heartbeatTick, 200L, heartbeatPeriod));

    long posPeriod = Math.max(1, cfg.collectorLong("positions", "min-update-seconds", 3)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, positions::tick, 40L, posPeriod));

    long statusPeriod = Math.max(15, cfg.longVal("status.heartbeat-seconds", 25)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, status::report, 20L, statusPeriod));

    long tpsPeriod = Math.max(5, cfg.collectorLong("tps", "sample-seconds", 10)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, tps::sample, tpsPeriod, tpsPeriod));

    long wlPoll = Math.max(2, cfg.collectorLong("whitelist", "command-poll-seconds", 4)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, whitelist::pollCommands, 60L, wlPoll));

    long wlMirror = Math.max(15, cfg.collectorLong("whitelist", "mirror-seconds", 120)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, whitelist::mirror, 100L, wlMirror));

    long chatPoll = Math.max(2, cfg.collectorLong("chat", "web-poll-seconds", 4)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, chat::poll, 80L, chatPoll));

    long achOffline = Math.max(30, cfg.collectorLong("achievements", "offline-scan-seconds", 900)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, achievements::scanOffline, 600L, achOffline));

    long reconcile = Math.max(1, cfg.collectorLong("stats", "reconcile-minutes", 15)) * 60L * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, stats::reconcile, reconcile, reconcile));

    long remotePoll = remotePollSeconds * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pollRemote, remotePoll, remotePoll));

    if (ops != null) {
      // Own cadence rather than a remote setting: the console mirror and the
      // command queue are diagnostic plumbing, and a remote config must not be
      // able to stop the server from reporting on itself.
      tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
        ops.writeStatus();
        ops.pollCommands();
      }, 100L, 100L));
      // Main thread: the summary reads the online player count.
      tasks.add(Bukkit.getScheduler().runTaskTimer(this, ops::summarize, 6000L, 6000L));

      // Applies the DB retention caps in purge_bridge_history(). Nothing else
      // runs it, so the plugin owns the schedule; first run 30s after start.
      long purgeHours = Math.max(1, getConfig().getLong("bridge.retention.purge-hours", 6));
      tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, ops::runRetentionPurge,
          600L, purgeHours * 3600L * 20L));
    }
  }

  private void cancelTasks() {
    for (BukkitTask task : tasks) {
      try {
        task.cancel();
      } catch (Exception ignored) {
      }
    }
    tasks.clear();
  }

  private void scheduleUpdateChecks() {
    if (updateTask != null) {
      try {
        updateTask.cancel();
      } catch (Exception ignored) {
      }
      updateTask = null;
    }
    if (updater == null || !updater.isEnabled()) return;
    // update.* is local config on purpose (a remote config must not be able to
    // start or retune the self-updater), so the remote document is not consulted.
    long periodSecs = getConfig().getLong("update.check-seconds", 90);
    long period = Math.max(20, periodSecs) * 20L;
    updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::updateTick, 200L, period);
  }

  private void updateTick() {
    // Updater already logs staged updates (with the apply hint) and failures, so
    // nothing is echoed back here - one message on the console, not two.
    updater.check().thenAccept(msg -> {
      if (updater.autoApply() && updater.hasStaged()) {
        Bukkit.getScheduler().runTask(this, () -> {
          getLogger().info("auto-applying staged update v" + updater.stagedVersion());
          updater.apply(Bukkit.getConsoleSender());
        });
      }
    });
  }

  private void pollRemote() {
    fetchRemote().thenAccept(cfg -> Bukkit.getScheduler().runTask(this, () -> {
      if (cfg.generation() != remoteGeneration.get()) return;
      applyConfig(cfg);
    }));
  }

  /** Starts a fetch tagged with the generation that is allowed to apply it. */
  private java.util.concurrent.CompletableFuture<RemoteConfig> fetchRemote() {
    long generation = remoteGeneration.incrementAndGet();
    return RemoteConfig.fetch(remoteUrl, overrides, remoteCachePath, getLogger(), this::masterEnabled)
        .thenApply(cfg -> cfg.withGeneration(generation));
  }

  private void applyConfig(RemoteConfig cfg) {
    remoteConfig = cfg;
    stats.invalidateSelection();
    if (ops != null) {
      JsonObject details = new JsonObject();
      details.addProperty("version", cfg.version());
      details.addProperty("source", cfg.source());
      details.addProperty("generation", cfg.generation());
      ops.report(cfg.version() > 0 ? "info" : "warn", "config",
          cfg.version() > 0 ? "config.applied" : "config.unavailable",
          cfg.version() > 0 ? "Remote config v" + cfg.version() + " applied (" + cfg.source() + ")"
              : "Remote config unavailable, keeping the last good copy",
          details);
    }
    if (!firstConfigApplied && cfg.version() > 0) {
      firstConfigApplied = true;
      sinks.flushAll();
      chat.notice("Server online");
    }
    // Re-scheduling on every poll restarted the long-period timers (the
    // 15-minute reconcile never reached its first run). Only rebuild tasks
    // when a value that is actually baked into them changed.
    rescheduleTasksIfChanged();
    scheduleUpdateChecksIfChanged();
    syncLiveMap();
    if (cfg.version() > 0) {
      // The first-tick catalog sync cannot run (remote config not loaded yet),
      // so re-publish the catalog whenever a real config is applied.
      try {
        achievements.syncCatalog();
      } catch (Exception ex) {
        getLogger().log(java.util.logging.Level.WARNING, "Achievement catalog sync failed", ex);
      }
    }
  }

  /**
   * Starts or stops the live map so its running state matches the effective
   * switch (remote config map.enabled once one has been applied, otherwise the
   * local config.yml value). Runs on the main thread via onEnable/applyConfig.
   */
  private void syncLiveMap() {
    boolean want = remoteConfig.version() > 0
        ? remoteConfig.mapEnabled()
        : getConfig().getBoolean("map.enabled", false);
    boolean have = liveMap != null;
    if (want == have) return;
    if (want) {
      liveMap = new LiveMap(this,
          new SupabaseStorage(supabaseUrl, supabaseKey, getLogger()),
          () -> masterEnabled);
      liveMap.start();
      getLogger().info("map: started (remote config enabled)");
    } else {
      liveMap.stop();
      liveMap = null;
      getLogger().info("map: stopped (remote config disabled)");
    }
  }

  private String scheduleFingerprint;

  /** Every scheduling input, in one comparable string. */
  private String scheduleFingerprint() {
    RemoteConfig cfg = remoteConfig;
    if (cfg == null) return "";
    return String.join("|",
        String.valueOf(cfg.collectorLong("presence", "heartbeat-seconds", 300)),
        String.valueOf(cfg.collectorLong("positions", "min-update-seconds", 3)),
        String.valueOf(cfg.longVal("status.heartbeat-seconds", 25)),
        String.valueOf(cfg.collectorLong("tps", "sample-seconds", 10)),
        String.valueOf(cfg.collectorLong("whitelist", "command-poll-seconds", 4)),
        String.valueOf(cfg.collectorLong("whitelist", "mirror-seconds", 120)),
        String.valueOf(cfg.collectorLong("chat", "web-poll-seconds", 4)),
        String.valueOf(cfg.collectorLong("achievements", "offline-scan-seconds", 900)),
        String.valueOf(cfg.collectorLong("achievements", "progress-scan-seconds", 60)),
        String.valueOf(cfg.collectorLong("stats", "reconcile-minutes", 15)));
  }

  private void rescheduleTasksIfChanged() {
    String fingerprint = scheduleFingerprint();
    if (fingerprint.equals(scheduleFingerprint)) return;
    scheduleFingerprint = fingerprint;
    rescheduleTasks();
  }

  private String updateFingerprint;

  private void scheduleUpdateChecksIfChanged() {
    long periodSecs = getConfig().getLong("update.check-seconds", 90);
    boolean on = updater != null && updater.isEnabled();
    String fingerprint = on + "@" + Math.max(20, periodSecs);
    if (fingerprint.equals(updateFingerprint)) return;
    updateFingerprint = fingerprint;
    scheduleUpdateChecks();
  }

  // ---------------------------------------------------------------- commands

  // Package-private accessors for BridgeOps. The website-facing diagnostics
  // need the same objects the in-game status line reads, and widening these
  // avoids duplicating that logic.

  SupabaseRest restApi() {
    return rest;
  }

  SinkManager sinkManager() {
    return sinks;
  }

  RemoteConfig config() {
    return remoteConfig;
  }

  ChatBridge chatBridge() {
    return chat;
  }

  WhitelistSync whitelistSync() {
    return whitelist;
  }

  AchievementCollector achievementCollector() {
    return achievements;
  }

  StatCollector statCollector() {
    return stats;
  }

  BridgeOps opsApi() {
    return ops;
  }

  LiveMap liveMap() {
    return liveMap;
  }

  /** The jar this plugin was loaded from (JavaPlugin#getFile is protected). */
  public java.io.File pluginFile() {
    return getFile();
  }

  /** Pushes every queued row now instead of waiting for the next flush cycle. */
  public void flushSinks(CommandSender sender) {
    sender.sendMessage("§e[MiaucraftBridge] Flushing queued rows...");
    int total = 0;
    for (String table : BridgeOps.TRACKED_SINKS) total += sinks.pending(table);
    if (total == 0) {
      sender.sendMessage("§a[MiaucraftBridge] Nothing queued - all sinks are already empty.");
      return;
    }
    final int queued = total;
    sinks.flushAll().whenComplete((ignored, err) -> Bukkit.getScheduler().runTask(this, () -> {
      if (err != null) {
        sender.sendMessage("§c[MiaucraftBridge] Flush failed: " + err.getMessage());
      } else {
        sender.sendMessage("§a[MiaucraftBridge] Flushed " + queued + " queued row(s).");
      }
    }));
  }

  public void reloadRemote(CommandSender sender) {
    sender.sendMessage("§e[MiaucraftBridge] Re-fetching remote config...");
    fetchRemote().thenAccept(cfg -> Bukkit.getScheduler().runTask(this, () -> {
      if (cfg.generation() != remoteGeneration.get()) {
        sender.sendMessage("§c[MiaucraftBridge] A newer fetch already landed - this one was ignored.");
        return;
      }
      applyConfig(cfg);
      sender.sendMessage("§a[MiaucraftBridge] Remote config v" + cfg.version()
          + " applied (" + cfg.source() + ").");
      resyncAchievementsCatalog(sender);
    }));
  }

  /** Re-publishes the static achievement catalog so schema/code changes land without a reboot. */
  public void resyncAchievementsCatalog(CommandSender sender) {
    sender.sendMessage("§e[MiaucraftBridge] Re-syncing achievement catalog...");
    try {
      achievements.syncCatalog();
      sender.sendMessage("§a[MiaucraftBridge] Achievement catalog re-synced.");
    } catch (Exception ex) {
      getLogger().log(java.util.logging.Level.WARNING, "Achievement catalog re-sync failed", ex);
      sender.sendMessage("§c[MiaucraftBridge] Achievement catalog re-sync failed: " + ex);
    }
  }

  public void checkUpdate(CommandSender sender) {
    sender.sendMessage("§e[MiaucraftBridge] Checking for updates...");
    updater.check().thenAccept(msg -> Bukkit.getScheduler().runTask(this,
        () -> sender.sendMessage("§a[MiaucraftBridge] " + msg)));
  }

  public void applyUpdate(CommandSender sender) {
    updater.apply(sender);
  }

  public void testConnection(CommandSender sender) {
    sender.sendMessage("§e[MiaucraftBridge] Testing Supabase connection...");
    rest.select("test", "players", "select=id&limit=1")
        .whenComplete((rows, err) -> Bukkit.getScheduler().runTask(this, () -> {
          if (err != null) {
            sender.sendMessage("§c[MiaucraftBridge] Connection failed: " + err.getMessage());
          } else {
            sender.sendMessage("§a[MiaucraftBridge] Connection OK (" + rows.size() + " row(s) read).");
          }
        }));
  }

  public void forceStats(CommandSender sender) {
    if (stats.isRunning()) {
      sender.sendMessage("§e[MiaucraftBridge] A stat reconcile is already running.");
      return;
    }
    sender.sendMessage("§e[MiaucraftBridge] Reconciling stats for online players...");
    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
      stats.reconcile();
      sinks.flushAll();
      Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(
          "§a[MiaucraftBridge] Done - " + stats.lastChanged() + " changed value(s) across "
              + stats.lastPlayers() + " player(s)."));
    });
  }

  public List<String> statusLines() {
    RemoteConfig cfg = remoteConfig;
    List<String> lines = new ArrayList<>();
    lines.add("§6[MiaucraftBridge] status");
    lines.add("§7remote: §f" + remoteUrl);
    lines.add("§7config: §f" + (cfg.version() > 0 ? "v" + cfg.version() + " (" + cfg.source() + ")" : "not loaded"));
    lines.add("§7master enabled: §f" + masterEnabled);
    lines.add("§7live map: §f" + (liveMap != null ? "on" : "off"));
    lines.add("§7supabase: §f" + restSchema + " §7(bridge traffic "
        + (rest.isEnabled() ? "§aon" : "§coff") + "§7)");
    lines.add("§7collectors: §f" + collectorSummary(cfg));
    lines.add("§7stat reconcile: §f" + (stats.isRunning() ? "running" : "idle"));
    lines.add("§7last whitelist mirror: §f" + ago(whitelist.lastMirrorMs()));
    lines.add("§7last achievement scan: §f" + ago(achievements.lastScanMs()));
    lines.add("§7last web chat relay: §f" + chat.lastRelayed() + " message(s)");
    for (String t : List.of("player_achievements", "player_achievement_criteria", "player_stats",
        "players", "live_positions", "achievements", "achievement_criteria", "server_tps_samples",
        "chat_messages", "whitelist_commands")) {
      int pend = sinks.pending(t);
      String err = sinks.lastFailure(t);
      if (pend == 0 && err == null) continue;
      lines.add("§7sink " + t + "§7: §f" + pend + " pending"
          + (err != null ? " §c(last: " + err + ")" : ""));
    }
    if (updater != null) lines.addAll(updater.statusLines());
    return lines;
  }

  /** Force-resend the queued achievement rows and print each raw HTTP result to chat. */
  public void drainAchievements(CommandSender sender) {
    sender.sendMessage("§e[MiaucraftBridge] Re-sending queued achievement rows (raw results below)...");
    Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
      drainTable(sender, "player_achievements", "player_id,achievement_key");
      drainTable(sender, "player_achievement_criteria", "player_id,achievement_key,criterion_key");
    });
  }

  private void drainTable(CommandSender sender, String table, String onConflict) {
    try {
      List<com.google.gson.JsonObject> rows = sinks.pendingRows(table);
      if (rows.isEmpty()) {
        chatReply(sender, "§7" + table + ": nothing pending, skipping.");
        return;
      }
      com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
      for (com.google.gson.JsonObject row : rows) arr.add(row);
      SupabaseRest.RawResult res = rest.rawUpsert(table, arr, onConflict).get(20, TimeUnit.SECONDS);
      if (res.error != null) {
        chatReply(sender, "§c" + table + " -> transport error: " + res.error);
      } else if (res.code >= 300) {
        chatReply(sender, "§c" + table + " -> HTTP " + res.code + " body: "
            + (res.body == null || res.body.isBlank() ? "--" : shortBody(res.body)));
      } else {
        chatReply(sender, "§a" + table + " -> HTTP " + res.code + " OK, " + rows.size()
            + " row(s) submitted");
      }
    } catch (Exception e) {
      chatReply(sender, "§c" + table + " -> probe exception: " + e);
    }
  }

  private String shortBody(String s) {
    return s.length() <= 400 ? s : s.substring(0, 400) + "...";
  }

  private void chatReply(CommandSender sender, String message) {
    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message));
  }

  private String collectorSummary(RemoteConfig cfg) {
    StringBuilder sb = new StringBuilder();
    for (String name : List.of("presence", "positions", "stats", "achievements", "status", "whitelist", "chat")) {
      if (sb.length() > 0) sb.append(", ");
      sb.append(name).append(cfg.collectorEnabled(name) ? "=on" : "=off");
    }
    return sb.toString();
  }

  private String ago(long millis) {
    if (millis <= 0) return "never";
    long seconds = (System.currentTimeMillis() - millis) / 1000L;
    return seconds + "s ago";
  }

  // ---------------------------------------------------------------- config

  private JsonObject readOverrides() {
    ConfigurationSection section = getConfig().getConfigurationSection("overrides");
    if (section == null) return new JsonObject();
    JsonElement el = gson.toJsonTree(toMap(section));
    return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
  }

  private Map<String, Object> toMap(ConfigurationSection section) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String key : section.getKeys(false)) {
      Object value = section.get(key);
      if (value instanceof ConfigurationSection child) {
        out.put(key, toMap(child));
      } else {
        out.put(key, value);
      }
    }
    return out;
  }
}