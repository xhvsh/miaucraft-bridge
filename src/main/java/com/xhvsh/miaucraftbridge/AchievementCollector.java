package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Achievements (Bukkit advancements): the static catalog plus each online
 * player's progress, including partial multi-criteria progress.
 *
 * Progress is read only for online players (that is the only way Bukkit
 * exposes AdvancementProgress), which also keeps a local test run from
 * touching production rows. A completion event updates instantly instead of
 * waiting for the next scan, and a persisted signature cache means a restart
 * does not re-upsert the whole catalog of already-completed achievements.
 */
public final class AchievementCollector implements Listener {

  private final org.bukkit.plugin.Plugin plugin;
  private final SinkManager sinks;
  private final Supplier<RemoteConfig> config;
  private final Logger log;

  // In-memory only (NOT persisted): remembers this session's last enqueued
  // state so unchanged progress isn't re-enqueued constantly. Deliberately
  // re-primed on every boot so a previously-failed/poisoned write always gets
  // retried after a restart instead of being skipped forever.
  private final java.util.concurrent.ConcurrentHashMap<String, String> achSig = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentHashMap<String, Boolean> critSig = new java.util.concurrent.ConcurrentHashMap<>();
  private final AtomicBoolean onlineArmed = new AtomicBoolean(false);
  private final AtomicBoolean offlineScanning = new AtomicBoolean(false);

  /** Catalog data, only ever read/written on the main thread. */
  private String catalogSignature = null;
  private final Map<String, List<String>> critNamesByKey = new HashMap<>();
  private final Map<String, Integer> critTotals = new HashMap<>();
  private final Map<String, Integer> critMin = new HashMap<>();
  private volatile OnlineJob onlineJob;
  private volatile long lastScanMs = 0L;

  public AchievementCollector(org.bukkit.plugin.Plugin plugin, SinkManager sinks, PersistedState state,
      Supplier<RemoteConfig> config, Logger log) {
    this.plugin = plugin;
    this.sinks = sinks;
    this.config = config;
    this.log = log;
  }

  public long lastScanMs() {
    return lastScanMs;
  }

  // -------------------------------------------------------------- catalog

  /**
   * Publishes the static achievement menu + criteria. Main thread.
   *
   * <p>The catalog only changes when a datapack does, but the remote config is
   * re-applied every few minutes, so the previous implementation re-enqueued
   * ~3000 identical rows on every poll. The signature check keeps a re-apply
   * free unless the catalog really changed.
   */
  public void syncCatalog() {
    syncCatalog(false);
  }

  public void syncCatalog(boolean force) {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("achievements") || !cfg.boolVal("collectors.achievements.catalog-sync", true)) {
      return;
    }
    // The fingerprint covers the rendered content, not just the key set: a
    // datapack (or resource pack) can retitle/re-icon an existing advancement
    // without adding or removing any, and the web has to pick that up.
    String signature = catalogSignature();
    if (!force && signature.equals(catalogSignature)) {
      log.fine("Achievement catalog unchanged, skipping re-publish.");
      return;
    }
    catalogSignature = signature;

    int achievements = 0;
    int criteria = 0;
    int skipped = 0;

    // Re-walk for the catalog maps the offline scan needs (main thread only).
    critNamesByKey.clear();
    critTotals.clear();
    critMin.clear();
    Iterator<Advancement> it = Bukkit.advancementIterator();
    while (it.hasNext()) {
      Advancement advancement = it.next();
      AdvancementDisplay display = advancement.getDisplay();
      if (display == null) {
        skipped++;
        continue;
      }
      String key = advancement.getKey().toString();
      critNamesByKey.put(key, new java.util.ArrayList<>(advancement.getCriteria()));
      critTotals.put(key, advancement.getCriteria().size());
      critMin.put(key, minCriteria(advancement));
      try {
        criteria += catalogRow(advancement, display);
        achievements++;
      } catch (Exception ex) {
        skipped++;
        log.log(Level.WARNING, "Skipping catalog entry " + advancement.getKey(), ex);
      }
    }
    log.info("Achievement catalog: " + achievements + " achievement(s), "
        + criteria + " criteria (skipped " + skipped + " non-displayable, errored, or any-of advancement(s)).");
  }

  /** Main thread: content hash of everything the catalog rows carry. */
  private String catalogSignature() {
    StringBuilder fingerprint = new StringBuilder();
    Iterator<Advancement> it = Bukkit.advancementIterator();
    while (it.hasNext()) {
      Advancement advancement = it.next();
      fingerprint.append(advancement.getKey()).append('|');
      AdvancementDisplay display = advancement.getDisplay();
      if (display != null) {
        try {
          fingerprint.append(PlainTextComponentSerializer.plainText().serialize(display.title())).append('|');
          fingerprint.append(PlainTextComponentSerializer.plainText().serialize(display.description())).append('|');
          fingerprint.append(display.frame().name()).append('|');
          fingerprint.append(display.isHidden()).append('|');
          fingerprint.append(display.icon() == null ? "" : display.icon().getType().name()).append('|');
        } catch (Exception ex) {
          fingerprint.append("display-error|");
        }
      }
      fingerprint.append(advancement.getCriteria().size()).append('|');
      for (String criterion : advancement.getCriteria()) {
        fingerprint.append(criterion).append(',');
      }
      Integer min = minCriteria(advancement);
      fingerprint.append('|').append(min == null ? -1 : min).append(';');
    }
    return Integer.toHexString(fingerprint.toString().hashCode()) + ":" + fingerprint.length();
  }

  /** Publishes one catalog row (+ its criteria). Never throws; min_criteria is best-effort. */
  private int catalogRow(Advancement advancement, AdvancementDisplay display) {
    String key = advancement.getKey().toString();

    JsonObject row = new JsonObject();
    row.addProperty("key", key);
    row.addProperty("title", PlainTextComponentSerializer.plainText().serialize(display.title()));
    row.addProperty("description", PlainTextComponentSerializer.plainText().serialize(display.description()));
    row.addProperty("frame", display.frame().name());
    row.addProperty("hidden", display.isHidden());
    if (display.icon() != null) {
      row.addProperty("icon", display.icon().getType().name());
    } else {
      row.add("icon", com.google.gson.JsonNull.INSTANCE);
    }
    row.addProperty("total_criteria", advancement.getCriteria().size());
    Integer min = minCriteria(advancement);
    if (min != null) {
      row.addProperty("min_criteria", min);
    } else if (log.isLoggable(Level.FINE)) {
      log.fine("No min_criteria for " + key + ", web will fall back to total criteria.");
    }
    sinks.sink("achievements", "key", true, "key").add(row);

    int added = 0;
    for (String criterion : advancement.getCriteria()) {
      JsonObject crow = new JsonObject();
      crow.addProperty("achievement_key", key);
      crow.addProperty("criterion_key", criterion);
      sinks.sink("achievement_criteria", "achievement_key,criterion_key", true,
          "achievement_key", "criterion_key").add(crow);
      added++;
    }
    return added;
  }

  /**
   * Number of requirement groups, i.e. the minimum criteria a player must
   * satisfy to complete the advancement. For "any of" advancements this is 1.
   * Returns null when the requirement layout is unavailable on the running
   * Paper build so the web falls back to total criteria.
   */
  private static Integer minCriteria(Advancement advancement) {
    try {
      return advancement.getRequirements().getRequirements().size();
    } catch (Exception ex) {
      return null;
    }
  }

  // --------------------------------------------------------------- events

  @EventHandler(priority = EventPriority.MONITOR)
  public void onAdvancementDone(PlayerAdvancementDoneEvent e) {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("achievements")) return;
    Advancement advancement = e.getAdvancement();
    if (advancement.getDisplay() == null) return;
    try {
      collectOne(e.getPlayer(), advancement);
    } catch (Exception ex) {
      log.log(Level.WARNING, "Instant achievement update failed for " + e.getPlayer().getName(), ex);
    }
  }

  /** Drops a quitting player's progress cache so it cannot grow forever. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
    String prefix = e.getPlayer().getUniqueId() + "|";
    achSig.keySet().removeIf(k -> k.startsWith(prefix));
    critSig.keySet().removeIf(k -> k.startsWith(prefix));
  }

  // --------------------------------------------------------------- scans

  /**
   * Arms a progress scan over the online players. Safe to call from any
   * thread - it only flips a flag. The roster is snapshotted and the work is
   * pumped in small main-thread slices by {@link #pumpOnlineScan()} (Bukkit
   * forbids reading players/advancements off the main thread).
   */
  public void scanOnline() {
    if (!config.get().collectorEnabled("achievements")) return;
    if (onlineJob != null) return;
    onlineArmed.set(true);
  }

  /** Main thread: starts an armed scan and advances it by one small slice. */
  public void pumpOnlineScan() {
    if (!config.get().collectorEnabled("achievements")) {
      onlineJob = null;
      onlineArmed.set(false);
      return;
    }
    OnlineJob job = onlineJob;
    if (job == null) {
      if (!onlineArmed.compareAndSet(true, false)) return;
      job = new OnlineJob();
      onlineJob = job;
    }
    try {
      if (job.step(System.nanoTime() + 2_000_000L)) {
        lastScanMs = System.currentTimeMillis();
        onlineJob = null;
        onlineArmed.set(false);
      }
    } catch (Exception ex) {
      log.log(Level.WARNING, "Achievement scan failed", ex);
      onlineJob = null;
      onlineArmed.set(false);
    }
  }

  public boolean onlineScanArmed() {
    return onlineJob != null || onlineArmed.get();
  }

  /** Main thread. Scans one player completely (used by the join path/tests). */
  public void scanPlayer(Player player) {
    Iterator<Advancement> it = Bukkit.advancementIterator();
    while (it.hasNext()) {
      Advancement advancement = it.next();
      if (advancement.getDisplay() == null) continue;
      collectOne(player, advancement);
    }
  }

  /**
   * A resumable (player, advancement) walk, advanced a slice at a time. Each
   * player gets a fresh advancement iterator: a single shared iterator is
   * consumed by the first player and every later player is skipped entirely.
   */
  private final class OnlineJob {
    private final List<Player> players = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
    private int playerIndex = 0;
    private Player current;
    private Iterator<Advancement> advancements;

    /** @return true when the whole roster has been covered. */
    boolean step(long deadlineNanos) {
      int visited = 0;
      while (System.nanoTime() < deadlineNanos) {
        if (current == null) {
          if (playerIndex >= players.size()) return true;
          current = players.get(playerIndex++);
          advancements = Bukkit.advancementIterator();
        }
        if (!advancements.hasNext()) {
          current = null;
          advancements = null;
          continue;
        }
        Advancement advancement = advancements.next();
        if (advancement.getDisplay() == null) continue;
        Player owner = current;
        try {
          collectOne(owner, advancement);
        } catch (Exception ex) {
          log.log(Level.FINE, "Achievement read failed for " + owner.getName(), ex);
        }
        if (++visited >= 64) return false;
      }
      return false;
    }
  }

  private void collectOne(Player player, Advancement advancement) {
    String playerId = player.getUniqueId().toString();
    String key = advancement.getKey().toString();
    AdvancementProgress progress = player.getAdvancementProgress(advancement);

    boolean done = progress.isDone();
    int total = advancement.getCriteria().size();
    int completedCount = progress.getAwardedCriteria().size();

    String signature = completedCount + "/" + total + "/" + done;
    String achKey = playerId + "|" + key;
    if (!achSig.getOrDefault(achKey, "").equals(signature)) {
      JsonObject row = new JsonObject();
      row.addProperty("player_id", playerId);
      row.addProperty("achievement_key", key);
      row.addProperty("completed", done);
      row.addProperty("criteria_done", completedCount);
      row.addProperty("criteria_total", total);
      if (done) {
        Date latestAward = null;
        for (String criterion : advancement.getCriteria()) {
          Date awarded = progress.getDateAwarded(criterion);
          if (awarded != null && (latestAward == null || awarded.after(latestAward))) {
            latestAward = awarded;
          }
        }
        if (latestAward != null) {
          row.addProperty("completed_at", latestAward.toInstant().toString());
        } else {
          row.add("completed_at", com.google.gson.JsonNull.INSTANCE);
        }
      } else {
        row.add("completed_at", com.google.gson.JsonNull.INSTANCE);
      }
      row.addProperty("updated_at", BridgeUtil.nowIso());
      sinks.sink("player_achievements", "player_id,achievement_key", true,
          "player_id", "achievement_key").add(row);
      achSig.put(achKey, signature);
    }

    for (String criterion : advancement.getCriteria()) {
      boolean criterionDone = progress.getAwardedCriteria().contains(criterion);
      String critKey = achKey + "|" + criterion;
      Boolean stored = critSig.get(critKey);
      if (stored != null && stored.booleanValue() == criterionDone) continue;

      JsonObject row = new JsonObject();
      row.addProperty("player_id", playerId);
      row.addProperty("achievement_key", key);
      row.addProperty("criterion_key", criterion);
      row.addProperty("done", criterionDone);
      Date awarded = progress.getDateAwarded(criterion);
      if (awarded != null) {
        row.addProperty("awarded_at", awarded.toInstant().toString());
      } else {
        row.add("awarded_at", com.google.gson.JsonNull.INSTANCE);
      }
      row.addProperty("updated_at", Instant.now().toString());
      sinks.sink("player_achievement_criteria", "player_id,achievement_key,criterion_key", true,
          "player_id", "achievement_key", "criterion_key").add(row);
      critSig.put(critKey, criterionDone);
    }
  }

  // ------------------------------------------------------------ offline scan

  /**
   * Reconciles achievements for players who aren't online by reading Minecraft's
   * on-disk advancement storage (<level>/advancements/<uuid>.json). Bukkit only
   * exposes live progress for online players, but the game writes the full
   * done/criteria state (with epoch-millis timestamps) to this file for every
   * player, so offline progress can be recovered from here. Shares the same
   * in-memory signatures as the online scan, so the two never double-enqueue.
   *
   * <p>All Bukkit access (world folder, catalog, names) is resolved on the main
   * thread first; the file walk itself is pure IO on this async task.
   */
  public void scanOffline() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("achievements")) return;
    if (!offlineScanning.compareAndSet(false, true)) return;
    try {
      OfflineContext context = offlineContext();
      if (context == null) return;
      File advDir = new File(context.worldFolder, "advancements");
      if (!advDir.isDirectory()) return;

      File[] files = advDir.listFiles();
      if (files == null) return;
      int players = 0;
      int changed = 0;
      for (File file : files) {
        String name = file.getName();
        if (!name.endsWith(".json")) continue;
        String playerId = name.substring(0, name.length() - 5);
        if (context.usernames == null || !context.usernames.containsKey(playerId)) continue;
        try {
          changed += collectOfflineFile(playerId, file, context.criteria, context.totals, context.mins);
          players++;
        } catch (Exception ex) {
          log.log(Level.WARNING, "Offline achievement scan failed for " + playerId, ex);
        }
      }
      lastScanMs = System.currentTimeMillis();
      if (cfg.debugLog()) {
        log.info("Offline achievement scan: " + players + " player(s), "
            + changed + " changed (total files " + files.length + ").");
      }
    } finally {
      offlineScanning.set(false);
    }
  }

  private record OfflineContext(File worldFolder,
      Map<String, List<String>> criteria,
      Map<String, Integer> totals,
      Map<String, Integer> mins,
      Map<String, String> usernames) {
  }

  /**
   * Resolves everything the async offline walk needs on the main thread: the
   * world folder, a copy of the catalog maps, and the id -> name mapping.
   */
  private OfflineContext offlineContext() {
    if (Bukkit.isPrimaryThread()) return buildOfflineContext();
    try {
      return Bukkit.getScheduler().callSyncMethod(plugin, this::buildOfflineContext)
          .get(10, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception e) {
      log.fine("Offline scan skipped (main-thread context unavailable): " + e.getMessage());
      return null;
    }
  }

  private OfflineContext buildOfflineContext() {
    File worldFolder = Bukkit.getWorlds().stream()
        .findFirst()
        .map(w -> w.getWorldFolder())
        .orElse(null);
    if (worldFolder == null) return null;
    Map<String, String> usernames = new java.util.HashMap<>();
    for (org.bukkit.OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
      String name = offline.getName();
      if (name != null) usernames.put(offline.getUniqueId().toString(), name);
    }
    return new OfflineContext(worldFolder,
        new HashMap<>(critNamesByKey), new HashMap<>(critTotals), new HashMap<>(critMin), usernames);
  }

  private int collectOfflineFile(String playerId, File file,
      Map<String, List<String>> critNamesByKey, Map<String, Integer> totals,
      Map<String, Integer> minByKey) throws Exception {
    JsonObject root;
    try {
      root = new com.google.gson.JsonParser().parse(Files.readString(file.toPath(), StandardCharsets.UTF_8))
          .getAsJsonObject();
    } catch (Exception e) {
      return 0;
    }
    int changed = 0;
    for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
      String key = entry.getKey();
      List<String> criteriaNames = critNamesByKey.get(key);
      if (criteriaNames == null) continue;
      Integer total = totals.get(key);
      if (total == null) continue;

      JsonObject val = entry.getValue().getAsJsonObject();
      boolean done = val.has("done") && val.get("done").getAsBoolean();
      long completedAtMs = 0L;
      java.util.Set<String> awarded = new java.util.HashSet<>();
      java.util.Map<String, Long> awardedAt = new HashMap<>();
      JsonObject criteria = val.has("criteria") ? val.getAsJsonObject("criteria") : null;
      if (criteria != null) {
        for (Map.Entry<String, JsonElement> c : criteria.entrySet()) {
          String name = c.getKey();
          long ts = readCriterionTimestamp(c.getValue());
          if (ts > 0L) {
            awarded.add(name);
            awardedAt.put(name, ts);
            completedAtMs = Math.max(completedAtMs, ts);
          } else if (isCriterionTruthy(c.getValue())) {
            awarded.add(name);
          }
        }
      }
      if (done && awarded.size() < total) {
        // A completed all-of advancement has every criterion done by
        // definition, and the save file sometimes omits criteria the server
        // didn't record individually (e.g. root advancements), so fill the
        // rest from the catalog. For any-of advancements the awarded criteria
        // ARE the truthful state: the rest are alternative paths that were
        // never taken, so leave them un-awarded.
        Integer min = minByKey.get(key);
        boolean allOf = min == null || min >= total;
        if (allOf) {
          for (String criterion : criteriaNames) {
            if (awarded.add(criterion)) {
              completedAtMs = Math.max(completedAtMs, awardedAt.getOrDefault(criterion, 0L));
            }
          }
        }
      }

      String signature = awarded.size() + "/" + total + "/" + done;
      String achKey = playerId + "|" + key;
      if (!achSig.getOrDefault(achKey, "").equals(signature)) {
        JsonObject row = new JsonObject();
        row.addProperty("player_id", playerId);
        row.addProperty("achievement_key", key);
        row.addProperty("completed", done);
        row.addProperty("criteria_done", awarded.size());
        row.addProperty("criteria_total", total);
        if (done && completedAtMs > 0L) {
          row.addProperty("completed_at", Instant.ofEpochMilli(completedAtMs).toString());
        } else {
          row.add("completed_at", com.google.gson.JsonNull.INSTANCE);
        }
        row.addProperty("updated_at", BridgeUtil.nowIso());
        sinks.sink("player_achievements", "player_id,achievement_key", true,
            "player_id", "achievement_key").add(row);
        achSig.put(achKey, signature);
        changed++;
      }

      for (String criterion : criteriaNames) {
        String critKey = achKey + "|" + criterion;
        boolean criterionDone = awarded.contains(criterion);
        Boolean stored = critSig.get(critKey);
        if (stored != null && stored.booleanValue() == criterionDone) continue;

        JsonObject row = new JsonObject();
        row.addProperty("player_id", playerId);
        row.addProperty("achievement_key", key);
        row.addProperty("criterion_key", criterion);
        row.addProperty("done", criterionDone);
        long ts = awardedAt.getOrDefault(criterion, 0L);
        if (criterionDone && ts > 0L) {
          row.addProperty("awarded_at", Instant.ofEpochMilli(ts).toString());
        } else {
          row.add("awarded_at", com.google.gson.JsonNull.INSTANCE);
        }
        row.addProperty("updated_at", BridgeUtil.nowIso());
        sinks.sink("player_achievement_criteria", "player_id,achievement_key,criterion_key", true,
            "player_id", "achievement_key", "criterion_key").add(row);
        critSig.put(critKey, criterionDone);
        changed++;
      }
    }
    return changed;
  }

  /** Best-effort timestamp from any plausible criterion value shape seen across
   *  MC save formats: number, numeric string, or an object with a numeric
   *  "time"-ish leaf. Returns 0 when no timestamp can be read. */
  static long readCriterionTimestamp(JsonElement el) {
    if (el == null) return 0L;
    if (el.isJsonPrimitive()) {
      com.google.gson.JsonPrimitive p = el.getAsJsonPrimitive();
      if (p.isNumber()) return p.getAsLong();
      if (p.isString()) {
        String s = p.getAsString().trim();
        if (s.isEmpty()) return 0L;
        try {
          return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
          // fall through to datetime shapes
        }
        for (String pat : new String[]{"yyyy-MM-dd HH:mm:ss Z", "yyyy-MM-dd HH:mm:ss z",
            "yyyy-MM-dd HH:mm:ss.SSS Z", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd HH:mm:ss"}) {
          try {
            // Explicit UTC: a zone-less timestamp in a save file must not be
            // reinterpreted by whatever timezone the server happens to run in.
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(pat, java.util.Locale.ROOT);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return fmt.parse(s).getTime();
          } catch (Exception ignored) {
            // try the next pattern
          }
        }
        return 0L;
      }
      return 0L;
    }
    if (el.isJsonObject()) {
      JsonObject o = el.getAsJsonObject();
      for (String key : new String[]{"time", "ts", "timestamp", "date", "when", "done_at", "completed_at"}) {
        if (o.has(key)) {
          long ts = readCriterionTimestamp(o.get(key));
          if (ts > 0L) return ts;
        }
      }
      long max = 0L;
      for (Map.Entry<String, JsonElement> e : o.entrySet()) {
        if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
          max = Math.max(max, e.getValue().getAsLong());
        }
      }
      return max;
    }
    return 0L;
  }

  static boolean isCriterionTruthy(JsonElement el) {
    if (el == null) return false;
    if (el.isJsonPrimitive()) {
      com.google.gson.JsonPrimitive p = el.getAsJsonPrimitive();
      if (p.isBoolean()) return p.getAsBoolean();
      if (p.isNumber()) return p.getAsLong() > 0L;
      if (p.isString()) {
        String s = p.getAsString().trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("yes");
      }
    }
    return false;
  }
}