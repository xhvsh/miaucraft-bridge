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
      "https://raw.githubusercontent.com/xhvsh/miaucraft/main/bridge/remote-config.json";

  private final List<BukkitTask> tasks = new ArrayList<>();
  private final Gson gson = new Gson();

  private String remoteUrl;
  private int remotePollSeconds;
  private Path remoteCachePath;
  private JsonObject overrides;
  private boolean masterEnabled;

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

  private volatile boolean firstConfigApplied = false;

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
    sinks.setEnabled(() -> masterEnabled);
    state = new PersistedState(getDataFolder().toPath().resolve("bridge-state.json"), getLogger());

    Supplier<RemoteConfig> cfg = () -> remoteConfig;
    remoteConfig = RemoteConfig.fromJson(null, overrides);

    chat = new ChatBridge(this, sinks, rest, state, cfg, getLogger());
    presence = new PresenceTracker(sinks, cfg, chat);
    positions = new LivePositionTracker(sinks, rest, cfg, getLogger());
    stats = new StatCollector(sinks, state, cfg, getLogger());
    achievements = new AchievementCollector(sinks, state, cfg, getLogger());
    status = new ServerStatusCollector(sinks, cfg);
    tps = new TpsSampler(sinks, cfg);
    whitelist = new WhitelistSync(this, sinks, rest, state, cfg, getLogger());
    updater = new Updater(this, remoteUrl);

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
  }

  @Override
  public void onDisable() {
    cancelTasks();
    if (updateTask != null) {
      try {
        updateTask.cancel();
      } catch (Exception ignored) {
      }
      updateTask = null;
    }
    if (rest == null) return;

    try {
      presence.shutdown();
      positions.shutdown();
      chat.notice("Server offline");
      sinks.flushAll().get(5, TimeUnit.SECONDS);
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

    tasks.add(Bukkit.getScheduler().runTaskTimer(this, presence::heartbeatTick, 200L, 1200L));

    long posPeriod = Math.max(1, cfg.collectorLong("positions", "min-update-seconds", 3)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, positions::tick, 40L, posPeriod));

    long statusPeriod = Math.max(15, cfg.longVal("status.heartbeat-seconds", 25)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, status::report, 20L, statusPeriod));

    long tpsPeriod = Math.max(5, cfg.collectorLong("tps", "sample-seconds", 10)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, tps::sample, tpsPeriod, tpsPeriod));

    long wlPoll = Math.max(2, cfg.collectorLong("whitelist", "command-poll-seconds", 4)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, whitelist::pollCommands, 60L, wlPoll));

    long wlMirror = Math.max(15, cfg.collectorLong("whitelist", "mirror-seconds", 120)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, whitelist::mirror, 100L, wlMirror));

    long chatPoll = Math.max(2, cfg.collectorLong("chat", "web-poll-seconds", 4)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, chat::poll, 80L, chatPoll));

    long achScan = Math.max(10, cfg.collectorLong("achievements", "progress-scan-seconds", 30)) * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimer(this, achievements::scanOnline, 200L, achScan));

    long reconcile = Math.max(1, cfg.collectorLong("stats", "reconcile-minutes", 15)) * 60L * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, stats::reconcile, reconcile, reconcile));

    long remotePoll = remotePollSeconds * 20L;
    tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pollRemote, remotePoll, remotePoll));
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
    long period = Math.max(60, getConfig().getInt("update.check-seconds", 300)) * 20L;
    updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::updateTick, 200L, period);
  }

  private void updateTick() {
    updater.check().thenAccept(msg -> {
      boolean notable = msg != null
          && (msg.startsWith("staged") || msg.startsWith("check failed"));
      if (notable || (remoteConfig != null && remoteConfig.debugLog())) {
        getLogger().info("update: " + msg);
      }
      if (updater.autoApply() && updater.hasStaged()) {
        Bukkit.getScheduler().runTask(this, () -> {
          getLogger().info("auto-applying staged update v" + updater.stagedVersion());
          updater.apply(Bukkit.getConsoleSender());
        });
      }
    });
  }

  private void pollRemote() {
    RemoteConfig.fetch(remoteUrl, overrides, remoteCachePath, getLogger())
        .thenAccept(cfg -> Bukkit.getScheduler().runTask(this, () -> applyConfig(cfg)));
  }

  private void applyConfig(RemoteConfig cfg) {
    remoteConfig = cfg;
    stats.invalidateSelection();
    if (!firstConfigApplied && cfg.version() > 0) {
      firstConfigApplied = true;
      sinks.flushAll();
      chat.notice("Server online");
    }
    rescheduleTasks();
  }

  // ---------------------------------------------------------------- commands

  /** The jar this plugin was loaded from (JavaPlugin#getFile is protected). */
  public java.io.File pluginFile() {
    return getFile();
  }

  public void reloadRemote(CommandSender sender) {
    sender.sendMessage("§e[MiaucraftBridge] Re-fetching remote config...");
    RemoteConfig.fetch(remoteUrl, overrides, remoteCachePath, getLogger())
        .thenAccept(cfg -> Bukkit.getScheduler().runTask(this, () -> {
          applyConfig(cfg);
          sender.sendMessage("§a[MiaucraftBridge] Remote config v" + cfg.version()
              + " applied (" + cfg.source() + ").");
        }));
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
    lines.add("§7collectors: §f" + collectorSummary(cfg));
    lines.add("§7stat reconcile: §f" + (stats.isRunning() ? "running" : "idle"));
    lines.add("§7last whitelist mirror: §f" + ago(whitelist.lastMirrorMs()));
    lines.add("§7last achievement scan: §f" + ago(achievements.lastScanMs()));
    lines.add("§7last web chat relay: §f" + chat.lastRelayed() + " message(s)");
    for (String t : List.of("player_achievements", "player_achievement_criteria", "player_stats",
        "players", "live_positions", "achievements", "achievement_criteria", "server_tps_samples")) {
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