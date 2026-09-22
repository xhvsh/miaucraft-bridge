package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Statistics collection, rebuilt around two rules the old plugin broke:
 *
 *   1. Stats are written the moment the game increments them
 *      (PlayerStatisticIncrementEvent) — no waiting for a scan.
 *   2. The periodic reconcile only ever walks ONLINE players, so a local test
 *      server can never march through the whole player base and rewrite rows
 *      that belong to production.
 *
 * A persisted per-player diff cache means a restart re-sends nothing; the old
 * plugin's "restart burst" (every value re-upserted) is gone.
 */
public final class StatCollector implements Listener {

  private final SinkManager sinks;
  private final PersistedState state;
  private final Supplier<RemoteConfig> config;
  private final Logger log;

  private final JsonObject cache;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final Set<String> knownPlayers = ConcurrentHashMap.newKeySet();

  private volatile long lastFinishedMs = 0L;
  private volatile int lastChanged = 0;
  private volatile int lastPlayers = 0;

  public StatCollector(SinkManager sinks, PersistedState state, Supplier<RemoteConfig> config, Logger log) {
    this.sinks = sinks;
    this.state = state;
    this.config = config;
    this.log = log;
    this.cache = state.object("stats");
  }

  public boolean isRunning() {
    return running.get();
  }

  public long lastFinishedMs() {
    return lastFinishedMs;
  }

  public int lastChanged() {
    return lastChanged;
  }

  public int lastPlayers() {
    return lastPlayers;
  }

  // ---------------------------------------------------------------- events

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onIncrement(PlayerStatisticIncrementEvent e) {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("stats") || !cfg.boolVal("collectors.stats.events", true)) return;

    Statistic stat = e.getStatistic();
    String key = keyFor(stat, e.getMaterial(), e.getEntityType());
    if (key == null) return;
    if (!selected(cfg, stat.name(), key)) return;
    long value = e.getNewValue();
    if (value <= 0) return;

    synchronized (state) {
      cache.addProperty(cacheKey(e.getPlayer().getUniqueId().toString(), key), value);
    }
    writeStat(e.getPlayer(), key, value);
  }

  private String keyFor(Statistic stat, Material material, EntityType entityType) {
    return switch (stat.getType()) {
      case UNTYPED -> stat.name();
      case BLOCK, ITEM -> material == null ? null : stat.name() + ":" + material.name();
      case ENTITY -> entityType == null ? null : stat.name() + ":" + entityType.name();
      default -> null;
    };
  }

  private void writeStat(Player p, String key, long value) {
    String id = p.getUniqueId().toString();
    if (knownPlayers.add(id)) {
      JsonObject playerRow = new JsonObject();
      playerRow.addProperty("id", id);
      playerRow.addProperty("username", p.getName());
      sinks.sink("players", "id", true, "id").add(playerRow);
    }
    JsonObject row = new JsonObject();
    row.addProperty("player_id", id);
    row.addProperty("stat_key", key);
    row.addProperty("stat_value", value);
    row.addProperty("updated_at", BridgeUtil.nowIso());
    sinks.sink("player_stats", "player_id,stat_key", true, "player_id,stat_key").add(row);
  }

  // -------------------------------------------------------------- reconcile

  /** Periodic pass over online players only; call off the main thread. */
  public void reconcile() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("stats")) return;
    if (!running.compareAndSet(false, true)) return;

    int changedRows = 0;
    int touchedPlayers = 0;
    try {
      boolean totals = cfg.boolVal("collectors.stats.totals", true);
      ArrayList<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
      for (Player p : online) {
        try {
          int changed = scanPlayer(p, cfg, totals);
          if (changed > 0) {
            touchedPlayers++;
            changedRows += changed;
          }
        } catch (Exception ex) {
          log.log(Level.WARNING, "Stat reconcile failed for " + p.getName(), ex);
        }
      }
      state.save();
      lastChanged = changedRows;
      lastPlayers = touchedPlayers;
      if (changedRows > 0 || cfg.debugLog()) {
        log.info("Stat reconcile: " + changedRows + " changed row(s) across "
            + touchedPlayers + " of " + online.size() + " online player(s).");
      } else {
        log.fine("Stat reconcile: no changes across "
            + online.size() + " online player(s).");
      }
    } finally {
      running.set(false);
      lastFinishedMs = System.currentTimeMillis();
    }
  }

  private int scanPlayer(Player p, RemoteConfig cfg, boolean totals) {
    String id = p.getUniqueId().toString();
    int changed = 0;

    long blocksMined = 0, mobKills = 0, crafted = 0, used = 0, broken = 0, pickedUp = 0, dropped = 0;

    for (Statistic stat : Statistic.values()) {
      if (stat.getType() != Statistic.Type.UNTYPED) continue;
      if (!selected(cfg, "UNTYPED", stat.name())) continue;
      long value = safeUntyped(p, stat);
      if (value == 0) continue;
      if (diff(id, stat.name(), value)) changed++;
    }

    for (Material material : Material.values()) {
      if (!material.isBlock()) continue;
      String key = "MINE_BLOCK:" + material.name();
      if (!selected(cfg, "MINE_BLOCK", key)) continue;
      long value = safeTyped(p, Statistic.MINE_BLOCK, material);
      if (value == 0) continue;
      blocksMined += value;
      if (diff(id, key, value)) changed++;
    }
    if (totals && blocksMined > 0 && diff(id, "BLOCKS_MINED_TOTAL", blocksMined)) changed++;

    for (EntityType type : EntityType.values()) {
      String key = "KILL_ENTITY:" + type.name();
      if (!selected(cfg, "KILL_ENTITY", key)) continue;
      long value = safeEntity(p, Statistic.KILL_ENTITY, type);
      if (value == 0) continue;
      mobKills += value;
      if (diff(id, key, value)) changed++;
    }
    if (totals && mobKills > 0 && diff(id, "MOB_KILLS_TOTAL", mobKills)) changed++;

    for (EntityType type : EntityType.values()) {
      String key = "ENTITY_KILLED_BY:" + type.name();
      if (!selected(cfg, "ENTITY_KILLED_BY", key)) continue;
      long value = safeEntity(p, Statistic.ENTITY_KILLED_BY, type);
      if (value == 0) continue;
      if (diff(id, key, value)) changed++;
    }

    for (Material material : Material.values()) {
      if (!material.isItem()) continue;
      String key = "CRAFT_ITEM:" + material.name();
      if (!selected(cfg, "CRAFT_ITEM", key)) continue;
      long value = safeTyped(p, Statistic.CRAFT_ITEM, material);
      if (value == 0) continue;
      crafted += value;
      if (diff(id, key, value)) changed++;
    }
    if (totals && crafted > 0 && diff(id, "ITEMS_CRAFTED_TOTAL", crafted)) changed++;

    for (Material material : Material.values()) {
      if (!material.isItem()) continue;
      String key = "USE_ITEM:" + material.name();
      if (!selected(cfg, "USE_ITEM", key)) continue;
      long value = safeTyped(p, Statistic.USE_ITEM, material);
      if (value == 0) continue;
      used += value;
      if (diff(id, key, value)) changed++;
    }
    if (totals && used > 0 && diff(id, "ITEMS_USED_TOTAL", used)) changed++;

    for (Material material : Material.values()) {
      if (!material.isItem()) continue;
      String key = "BREAK_ITEM:" + material.name();
      if (!selected(cfg, "BREAK_ITEM", key)) continue;
      long value = safeTyped(p, Statistic.BREAK_ITEM, material);
      if (value == 0) continue;
      broken += value;
      if (diff(id, key, value)) changed++;
    }
    if (totals && broken > 0 && diff(id, "ITEMS_BROKEN_TOTAL", broken)) changed++;

    for (Material material : Material.values()) {
      if (!material.isItem()) continue;
      String key = "PICKUP:" + material.name();
      if (!selected(cfg, "PICKUP", key)) continue;
      long value = safeTyped(p, Statistic.PICKUP, material);
      if (value == 0) continue;
      pickedUp += value;
      if (diff(id, key, value)) changed++;
    }
    if (totals && pickedUp > 0 && diff(id, "ITEMS_PICKED_UP_TOTAL", pickedUp)) changed++;

    for (Material material : Material.values()) {
      if (!material.isItem()) continue;
      String key = "DROP:" + material.name();
      if (!selected(cfg, "DROP", key)) continue;
      long value = safeTyped(p, Statistic.DROP, material);
      if (value == 0) continue;
      dropped += value;
      if (diff(id, key, value)) changed++;
    }
    if (totals && dropped > 0 && diff(id, "ITEMS_DROPPED_TOTAL", dropped)) changed++;

    if (changed > 0) {
      knownPlayers.add(id);
      JsonObject playerRow = new JsonObject();
      playerRow.addProperty("id", id);
      playerRow.addProperty("username", p.getName());
      sinks.sink("players", "id", true, "id").add(playerRow);
    }
    return changed;
  }

  private long safeUntyped(Player p, Statistic stat) {
    try {
      return p.getStatistic(stat);
    } catch (Exception e) {
      return 0;
    }
  }

  private long safeTyped(Player p, Statistic stat, Material material) {
    try {
      return p.getStatistic(stat, material);
    } catch (Exception e) {
      return 0;
    }
  }

  private long safeEntity(Player p, Statistic stat, EntityType type) {
    try {
      return p.getStatistic(stat, type);
    } catch (Exception e) {
      return 0;
    }
  }

  private boolean diff(String playerId, String key, long value) {
    String cacheKey = cacheKey(playerId, key);
    synchronized (state) {
      JsonElement el = cache.get(cacheKey);
      if (el != null && el.isJsonPrimitive() && el.getAsLong() == value) return false;
      cache.addProperty(cacheKey, value);
      return true;
    }
  }

  private static String cacheKey(String playerId, String key) {
    return playerId + "|" + key;
  }

  // -------------------------------------------------------------- selection

  /** null means "everything in this category is allowed". */
  private Set<String> selection(RemoteConfig cfg, String category) {
    JsonArray arr = cfg.arrayAt("collectors.stats.categories." + category, null);
    if (arr == null || arr.size() == 0) return null;
    Set<String> set = new HashSet<>();
    for (JsonElement el : arr) {
      String v = el.getAsString();
      if (v.equalsIgnoreCase("all")) return null;
      set.add(v);
    }
    return set;
  }

  private final Map<String, Set<String>> selectionCache = new ConcurrentHashMap<>();

  private boolean selected(RemoteConfig cfg, String category, String key) {
    Set<String> sel = selectionCache.computeIfAbsent(category, c -> selection(cfg, c));
    return sel == null || sel.contains(key);
  }

  /** Called when a new remote config lands so category edits take effect. */
  public void invalidateSelection() {
    selectionCache.clear();
  }
}