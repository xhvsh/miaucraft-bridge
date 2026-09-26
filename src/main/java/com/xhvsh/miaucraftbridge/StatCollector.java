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
import org.bukkit.plugin.Plugin;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Statistics collection, rebuilt around two rules the old plugin broke:
 *
 *   1. Stats are written the moment the game increments them
 *      (PlayerStatisticIncrementEvent) - no waiting for a scan.
 *   2. The periodic reconcile only ever walks ONLINE players, so a local test
 *      server can never march through the whole player base and rewrite rows
 *      that belong to production.
 *
 * <p>The reconcile snapshots Bukkit data on the main thread in small slices
 * (Paper forbids API access from async tasks) and then diffs/appends off the
 * main thread. A value is written into the persisted diff cache only after the
 * row has actually been accepted by the database, so a failed flush is retried
 * instead of being silently forgotten.
 */
public final class StatCollector implements Listener {

  /** Probes read per main-thread slice; keeps each slice to a fraction of a tick. */
  private static final int SLICE_PROBES = 1500;

  private static final long SLICE_TIMEOUT_SECONDS = 10L;

  private final Plugin plugin;
  private final SinkManager sinks;
  private final PersistedState state;
  private final Supplier<RemoteConfig> config;
  private final Logger log;

  /** Confirmed (delivered) values, persisted so restarts do not re-flush history. */
  private final JsonObject confirmed;
  /** Values enqueued this session, delivered or not; drives change detection. */
  private final Map<String, Long> enqueued = new ConcurrentHashMap<>();
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final Set<String> playersEnqueued = ConcurrentHashMap.newKeySet();

  private volatile boolean stateDirty = false;
  private volatile long lastFinishedMs = 0L;
  private volatile int lastChanged = 0;
  private volatile int lastPlayers = 0;

  public StatCollector(Plugin plugin, SinkManager sinks, PersistedState state,
      Supplier<RemoteConfig> config, Logger log) {
    this.plugin = plugin;
    this.sinks = sinks;
    this.state = state;
    this.config = config;
    this.log = log;
    this.confirmed = state.object("stats");
    // Values the database already accepted are the baseline: without this a
    // restart would diff every online player's stats against an empty map and
    // re-queue the entire history on the first reconcile.
    for (Map.Entry<String, JsonElement> entry : confirmed.entrySet()) {
      JsonElement value = entry.getValue();
      if (value == null || !value.isJsonPrimitive()) continue;
      try {
        enqueued.put(entry.getKey(), value.getAsLong());
      } catch (NumberFormatException ignored) {
        // A corrupt cache entry is simply not part of the baseline.
      }
    }
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

    ensurePlayerRow(e.getPlayer().getUniqueId().toString(), e.getPlayer().getName());
    record(e.getPlayer().getUniqueId().toString(), key, value);
  }

  private String keyFor(Statistic stat, Material material, EntityType entityType) {
    return switch (stat.getType()) {
      case UNTYPED -> stat.name();
      case BLOCK, ITEM -> material == null ? null : stat.name() + ":" + material.name();
      case ENTITY -> entityType == null ? null : stat.name() + ":" + entityType.name();
      default -> null;
    };
  }

  private void confirm(String cacheKey, long value) {
    synchronized (state) {
      confirmed.addProperty(cacheKey, value);
    }
    stateDirty = true;
  }

  private static String cacheKey(String playerId, String key) {
    return playerId + "|" + key;
  }

  // -------------------------------------------------------------- reconcile

  /**
   * Walks online players in bounded main-thread slices, then diffs and upserts
   * the captured values off the main thread. Safe to call from an async task.
   */
  public void reconcile() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("stats")) return;
    if (!running.compareAndSet(false, true)) return;

    int changedRows = 0;
    int touchedPlayers = 0;
    try {
      boolean totals = cfg.boolVal("collectors.stats.totals", true);
      Cursor cursor = new Cursor(totals);
      Slice slice = slice(cursor, cfg);
      while (!slice.done()) {
        if (!cfg.collectorEnabled("stats")) break;
        PlayerRows player = slice.player();
        if (player != null) {
          boolean touched = false;
          for (Sample sample : player.samples()) {
            if (record(player.playerId(), sample.key(), sample.value())) {
              changedRows++;
              touched = true;
            }
          }
          if (player.totals() != null) {
            for (Sample sample : player.totals()) {
              if (record(player.playerId(), sample.key(), sample.value())) {
                changedRows++;
                touched = true;
              }
            }
          }
          if (touched) {
            ensurePlayerRow(player.playerId(), player.username());
            touchedPlayers++;
          }
        }
        slice = slice(cursor, cfg);
      }
      flushState();
      lastChanged = changedRows;
      lastPlayers = touchedPlayers;
      if (changedRows > 0 || cfg.debugLog()) {
        log.info("Stat reconcile: " + changedRows + " changed row(s) across "
            + touchedPlayers + " online player(s).");
      } else {
        log.fine("Stat reconcile: no changes across " + slice.playersSeen() + " online player(s).");
      }
    } catch (Exception ex) {
      log.log(Level.WARNING, "Stat reconcile failed", ex);
    } finally {
      running.set(false);
      lastFinishedMs = System.currentTimeMillis();
    }
  }

  private void ensurePlayerRow(String id, String username) {
    if (!playersEnqueued.add(id)) return;
    JsonObject playerRow = new JsonObject();
    playerRow.addProperty("id", id);
    playerRow.addProperty("username", username);
    sinks.sink("players", "id", true, "id").add(playerRow);
  }

  /** Runs one bounded slice of Bukkit reads on the main thread. */
  private Slice slice(Cursor cursor, RemoteConfig cfg) {
    if (Bukkit.isPrimaryThread()) return cursor.next(cfg, SLICE_PROBES);
    try {
      return Bukkit.getScheduler()
          .callSyncMethod(plugin, (Callable<Slice>) () -> cursor.next(cfg, SLICE_PROBES))
          .get(SLICE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception e) {
      log.log(Level.WARNING, "Stat reconcile slice failed: " + e.getMessage());
      cursor.abort();
      return Slice.finished();
    }
  }

  private void flushState() {
    if (stateDirty) {
      state.save();
      stateDirty = false;
    }
  }

  private record Sample(String key, long value) {
  }

  private record PlayerRows(String playerId, String username, List<Sample> samples, List<Sample> totals) {
  }

  private record Slice(PlayerRows player, boolean done, int playersSeen) {
    static Slice finished() {
      return new Slice(null, true, 0);
    }
  }

  /**
   * Main-thread cursor over the online roster: probes are read in chunks so
   * each slice stays small, Player/Statistic objects never leave the main
   * thread, and the async caller only ever sees immutable snapshots.
   */
  private final class Cursor {
    private static final String[] TYPED_CATEGORIES = {
        "MINE_BLOCK", "KILL_ENTITY", "ENTITY_KILLED_BY",
        "CRAFT_ITEM", "USE_ITEM", "BREAK_ITEM", "PICKUP", "DROP"
    };
    private static final Map<String, String> TOTALS = Map.of(
        "MINE_BLOCK", "BLOCKS_MINED_TOTAL",
        "KILL_ENTITY", "MOB_KILLS_TOTAL",
        "CRAFT_ITEM", "ITEMS_CRAFTED_TOTAL",
        "USE_ITEM", "ITEMS_USED_TOTAL",
        "BREAK_ITEM", "ITEMS_BROKEN_TOTAL",
        "PICKUP", "ITEMS_PICKED_UP_TOTAL",
        "DROP", "ITEMS_DROPPED_TOTAL");

    private record Probe(String category, String key, Statistic stat, Material material, EntityType entity) {
    }

    private final boolean totals;
    private final List<Probe> plan = new ArrayList<>();
    private final List<Player> players = new ArrayList<>();
    private int playerIndex = 0;
    private int probeIndex = 0;
    private boolean rosterLoaded = false;
    private boolean aborted = false;
    private long deadlineNanos = 0L;
    private List<Sample> samples = new ArrayList<>();
    private Map<String, Long> sums = new java.util.HashMap<>();

    Cursor(boolean totals) {
      this.totals = totals;
      buildPlan();
    }

    /** The read plan is static data, so it is resolved once per reconcile. */
    private void buildPlan() {
      for (Statistic stat : Statistic.values()) {
        if (stat.getType() == Statistic.Type.UNTYPED) {
          plan.add(new Probe("UNTYPED", stat.name(), stat, null, null));
        }
      }
      for (Material material : Material.values()) {
        if (material.isBlock()) {
          plan.add(new Probe("MINE_BLOCK", "MINE_BLOCK:" + material.name(), Statistic.MINE_BLOCK, material, null));
        }
      }
      for (EntityType entity : EntityType.values()) {
        plan.add(new Probe("KILL_ENTITY", "KILL_ENTITY:" + entity.name(), Statistic.KILL_ENTITY, null, entity));
        plan.add(new Probe("ENTITY_KILLED_BY", "ENTITY_KILLED_BY:" + entity.name(), Statistic.ENTITY_KILLED_BY, null, entity));
      }
      for (Material material : Material.values()) {
        if (!material.isItem()) continue;
        plan.add(new Probe("CRAFT_ITEM", "CRAFT_ITEM:" + material.name(), Statistic.CRAFT_ITEM, material, null));
        plan.add(new Probe("USE_ITEM", "USE_ITEM:" + material.name(), Statistic.USE_ITEM, material, null));
        plan.add(new Probe("BREAK_ITEM", "BREAK_ITEM:" + material.name(), Statistic.BREAK_ITEM, material, null));
        plan.add(new Probe("PICKUP", "PICKUP:" + material.name(), Statistic.PICKUP, material, null));
        plan.add(new Probe("DROP", "DROP:" + material.name(), Statistic.DROP, material, null));
      }
    }

    void abort() {
      aborted = true;
    }

    int playersSeen() {
      return players.size();
    }

    boolean done() {
      return aborted || (rosterLoaded && playerIndex >= players.size());
    }

    Slice next(RemoteConfig cfg, int budget) {
      if (aborted) return Slice.finished();
      if (!rosterLoaded) {
        players.addAll(Bukkit.getOnlinePlayers());
        rosterLoaded = true;
        if (players.isEmpty()) return new Slice(null, true, 0);
      }
      Player p = players.get(playerIndex);
      deadlineNanos = System.nanoTime() + 2_000_000L;

      int reads = 0;
      while (probeIndex < plan.size() && reads < budget) {
        if (reads > 0 && (reads & 63) == 0 && System.nanoTime() > deadlineNanos) break;
        read(p, cfg, plan.get(probeIndex));
        probeIndex++;
        reads++;
      }
      if (probeIndex < plan.size()) return new Slice(null, false, players.size());

      String id = p.getUniqueId().toString();
      String username = p.getName();
      List<Sample> playerSamples = samples;
      List<Sample> totalRows = new ArrayList<>();
      if (totals) {
        for (String category : TYPED_CATEGORIES) {
          long sum = sums.getOrDefault(category, 0L);
          if (sum > 0) totalRows.add(new Sample(TOTALS.get(category), sum));
        }
      }
      samples = new ArrayList<>();
      sums = new java.util.HashMap<>();
      playerIndex++;
      probeIndex = 0;

      boolean done = playerIndex >= players.size();
      return new Slice(new PlayerRows(id, username, playerSamples, totalRows), done, players.size());
    }

    private void read(Player p, RemoteConfig cfg, Probe probe) {
      if (!selected(cfg, probe.category(), probe.key())) return;
      long value = probe.material() != null
          ? safeTyped(p, probe.stat(), probe.material())
          : probe.entity() != null
              ? safeEntity(p, probe.stat(), probe.entity())
              : safeUntyped(p, probe.stat());
      if (value <= 0) return;
      samples.add(new Sample(probe.key(), value));
      if (TOTALS.containsKey(probe.category())) sums.merge(probe.category(), value, Long::sum);
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
  }

  /** True when the value was actually enqueued (i.e. it changed). */
  private boolean record(String playerId, String key, long value) {
    String ck = cacheKey(playerId, key);
    Long prev = enqueued.get(ck);
    if (prev != null && prev.longValue() == value) return false;
    enqueued.put(ck, value);

    JsonObject row = new JsonObject();
    row.addProperty("player_id", playerId);
    row.addProperty("stat_key", key);
    row.addProperty("stat_value", value);
    row.addProperty("updated_at", BridgeUtil.nowIso());
    sinks.sink("player_stats", "player_id,stat_key", true, "player_id", "stat_key")
        .add(row, () -> confirm(ck, value));
    return true;
  }

  // -------------------------------------------------------------- selection

  /** Sentinel for "every statistic in this category is allowed". */
  private static final Set<String> ALLOW_ALL = Set.of();

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
    // computeIfAbsent never caches a null mapping, so "allow all" is stored as
    // a sentinel - otherwise every probe of a permissive category would re-read
    // the remote config.
    Set<String> sel = selectionCache.computeIfAbsent(category, c -> {
      Set<String> resolved = selection(cfg, c);
      return resolved == null ? ALLOW_ALL : resolved;
    });
    return sel == ALLOW_ALL || sel.contains(key);
  }

  /** Called when a new remote config lands so category edits take effect. */
  public void invalidateSelection() {
    selectionCache.clear();
  }
}
