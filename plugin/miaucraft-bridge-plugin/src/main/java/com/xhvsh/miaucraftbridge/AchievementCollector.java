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

  private final SinkManager sinks;
  private final Supplier<RemoteConfig> config;
  private final Logger log;

  // In-memory only (NOT persisted): remembers this session's last enqueued
  // state so unchanged progress isn't re-enqueued constantly. Deliberately
  // re-primed on every boot so a previously-failed/poisoned write always gets
  // retried after a restart instead of being skipped forever.
  private final java.util.concurrent.ConcurrentHashMap<String, String> achSig = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.concurrent.ConcurrentHashMap<String, Boolean> critSig = new java.util.concurrent.ConcurrentHashMap<>();
  private final AtomicBoolean scanning = new AtomicBoolean(false);
  private volatile long lastScanMs = 0L;

  public AchievementCollector(SinkManager sinks, PersistedState state, Supplier<RemoteConfig> config, Logger log) {
    this.sinks = sinks;
    this.config = config;
    this.log = log;
  }

  public long lastScanMs() {
    return lastScanMs;
  }

  // -------------------------------------------------------------- catalog

  /** Publishes the static achievement menu + criteria. Main thread, once. */
  public void syncCatalog() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("achievements") || !cfg.boolVal("collectors.achievements.catalog-sync", true)) {
      return;
    }
    int achievements = 0;
    int criteria = 0;
    int skipped = 0;

    Iterator<Advancement> it = Bukkit.advancementIterator();
    while (it.hasNext()) {
      Advancement advancement = it.next();
      AdvancementDisplay display = advancement.getDisplay();
      if (display == null) {
        skipped++;
        continue;
      }
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

  // --------------------------------------------------------------- scans

  /** Periodic progress scan over online players; call off the main thread. */
  public void scanOnline() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("achievements")) return;
    if (!scanning.compareAndSet(false, true)) return;
    try {
      int scanned = 0;
      for (Player p : new java.util.ArrayList<>(Bukkit.getOnlinePlayers())) {
        try {
          scanPlayer(p);
          scanned++;
        } catch (Exception ex) {
          log.log(Level.WARNING, "Achievement scan failed for " + p.getName(), ex);
        }
      }
      lastScanMs = System.currentTimeMillis();
      if (scanned > 0) {
        if (cfg.debugLog()) {
          log.info("Achievement scan covered " + scanned + " online player(s).");
        } else {
          log.fine("Achievement scan covered " + scanned + " online player(s).");
        }
      }
    } finally {
      scanning.set(false);
    }
  }

  public void scanPlayer(Player player) {
    Iterator<Advancement> it = Bukkit.advancementIterator();
    while (it.hasNext()) {
      Advancement advancement = it.next();
      if (advancement.getDisplay() == null) continue;
      collectOne(player, advancement);
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
   */
  public void scanOffline() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("achievements")) return;
    if (!scanning.compareAndSet(false, true)) return;
    try {
      File worldFolder = Bukkit.getWorlds().stream().findFirst()
          .map(w -> w.getWorldFolder()).orElse(null);
      if (worldFolder == null) return;
      File advDir = new File(worldFolder, "advancements");
      if (!advDir.isDirectory()) return;

      Map<String, List<String>> critNamesByKey = new HashMap<>();
      Map<String, Integer> totals = new HashMap<>();
      Iterator<Advancement> it = Bukkit.advancementIterator();
      while (it.hasNext()) {
        Advancement advancement = it.next();
        if (advancement.getDisplay() == null) continue;
        String key = advancement.getKey().toString();
        List<String> names = new java.util.ArrayList<>(advancement.getCriteria());
        critNamesByKey.put(key, names);
        totals.put(key, names.size());
      }

      File[] files = advDir.listFiles();
      if (files == null) return;
      int players = 0;
      int changed = 0;
      for (File file : files) {
        String name = file.getName();
        if (!name.endsWith(".json")) continue;
        String playerId = name.substring(0, name.length() - 5);
        UUID uuid;
        try {
          uuid = UUID.fromString(playerId);
        } catch (Exception e) {
          continue;
        }
        org.bukkit.OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        if (player.getName() == null) continue;
        try {
          changed += collectOfflineFile(playerId, file, critNamesByKey, totals);
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
      scanning.set(false);
    }
  }

  private int collectOfflineFile(String playerId, File file,
      Map<String, List<String>> critNamesByKey, Map<String, Integer> totals) throws Exception {
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
          long ts = readCriterionTimestamp(c.getValue());
          if (ts > 0L) {
            awarded.add(c.getKey());
            awardedAt.put(c.getKey(), ts);
            completedAtMs = Math.max(completedAtMs, ts);
          } else if (isCriterionTruthy(c.getValue())) {
            awarded.add(c.getKey());
          }
        }
      }
      if (done && awarded.size() < total) {
        // A completed advancement has every criterion done by definition. The
        // save file doesn't always carry a per-criterion entry (or any at all,
        // e.g. root advancements), so fill the rest from the catalog when the
        // file marks the whole thing done.
        for (String criterion : criteriaNames) {
          if (awarded.add(criterion)) {
            completedAtMs = Math.max(completedAtMs, awardedAt.getOrDefault(criterion, 0L));
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
  private static long readCriterionTimestamp(JsonElement el) {
    if (el == null) return 0L;
    if (el.isJsonPrimitive()) {
      com.google.gson.JsonPrimitive p = el.getAsJsonPrimitive();
      if (p.isNumber()) return p.getAsLong();
      if (p.isString()) {
        String s = p.getAsString().trim();
        if (s.isEmpty()) return 0L;
        try {
          return Long.parseLong(s);
        } catch (NumberFormatException e) {
          return 0L;
        }
      }
      return 0L;
    }
    if (el.isJsonObject()) {
      JsonObject o = el.getAsJsonObject();
      for (String key : new String[]{"time", "ts", "timestamp", "date", "when"}) {
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

  private static boolean isCriterionTruthy(JsonElement el) {
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