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

import java.time.Instant;
import java.util.Date;
import java.util.Iterator;
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
      sinks.sink("achievements", "key", true, "key").add(row);
      achievements++;

      for (String criterion : advancement.getCriteria()) {
        JsonObject crow = new JsonObject();
        crow.addProperty("achievement_key", key);
        crow.addProperty("criterion_key", criterion);
        sinks.sink("achievement_criteria", "achievement_key,criterion_key", true,
            "achievement_key", "criterion_key").add(crow);
        criteria++;
      }
    }
    log.info("Achievement catalog: " + achievements + " achievement(s), "
        + criteria + " criteria (skipped " + skipped + " non-displayable advancement(s)).");
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
}