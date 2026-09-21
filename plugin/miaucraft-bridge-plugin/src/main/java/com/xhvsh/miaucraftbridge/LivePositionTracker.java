package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Live position publishing. Two rules make this drastically cheaper than the
 * old every-2s full-roster upsert:
 *   1. only a physical block / dimension change can mark a write pending,
 *   2. per-player min interval throttles how often a moving player's row is
 *      actually written. Live tracking can additionally be switched per
 *      player off from the website.
 */
public final class LivePositionTracker implements Listener {

  private record PosSnapshot(String dimension, double x, double y, double z) {
    @Override
    public boolean equals(Object o) {
      if (!(o instanceof PosSnapshot other)) return false;
      return dimension.equals(other.dimension) && x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(dimension, x, y, z);
    }
  }

  private final SinkManager sinks;
  private final SupabaseRest rest;
  private final Supplier<RemoteConfig> config;
  private final Logger log;

  private final Map<UUID, Long> lastWriteMs = new ConcurrentHashMap<>();
  private final Map<UUID, PosSnapshot> lastWrittenPos = new ConcurrentHashMap<>();
  private final Set<UUID> trackingDisabled = ConcurrentHashMap.newKeySet();
  private long lastTrackingRefreshMs = 0L;

  public LivePositionTracker(SinkManager sinks, SupabaseRest rest, Supplier<RemoteConfig> config, Logger log) {
    this.sinks = sinks;
    this.rest = rest;
    this.config = config;
    this.log = log;
  }

  private boolean enabled() {
    return config.get().collectorEnabled("positions");
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent e) {
    lastWriteMs.put(e.getPlayer().getUniqueId(), 0L);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent e) {
    UUID uid = e.getPlayer().getUniqueId();
    lastWriteMs.remove(uid);
    lastWrittenPos.remove(uid);
    trackingDisabled.remove(uid);
    deletePosition(uid);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onMove(PlayerMoveEvent e) {
    UUID uid = e.getPlayer().getUniqueId();
    long now = System.currentTimeMillis();
    if (!enabled() || trackingDisabled.contains(uid)) return;

    Location from = e.getFrom();
    Location to = e.getTo();
    if (to == null) return;
    if (from.getWorld() == to.getWorld()
        && from.getBlockX() == to.getBlockX()
        && from.getBlockY() == to.getBlockY()
        && from.getBlockZ() == to.getBlockZ()) {
      return;
    }

    long minMs = config.get().collectorLong("positions", "min-update-seconds", 3) * 1000L;
    Long last = lastWriteMs.get(uid);
    if (last != null && now - last < minMs) return;

    write(uid, to);
    lastWriteMs.put(uid, now);
  }

  private void write(UUID uid, Location loc) {
    String dim = BridgeUtil.dimensionOf(loc.getWorld());
    if (dim == null) return;
    PosSnapshot cur = new PosSnapshot(dim,
        BridgeUtil.round(loc.getX(), 3), BridgeUtil.round(loc.getY(), 3), BridgeUtil.round(loc.getZ(), 3));
    PosSnapshot prev = lastWrittenPos.get(uid);
    if (cur.equals(prev)) return;
    lastWrittenPos.put(uid, cur);

    JsonObject row = new JsonObject();
    row.addProperty("player_id", uid.toString());
    row.addProperty("dimension", dim);
    row.addProperty("x", cur.x);
    row.addProperty("y", cur.y);
    row.addProperty("z", cur.z);
    row.addProperty("updated_at", BridgeUtil.nowIso());
    sinks.sink("live_positions", "player_id", true, "player_id").add(row);
  }

  private void deletePosition(UUID uid) {
    rest.delete("live_positions", "live_positions", "player_id=eq." + uid)
        .exceptionally(err -> null);
  }

  /** Periodic maintenance tick (also used as the tracking-refresh cadence). */
  public void tick() {
    if (enabled()) refreshTrackingDisabled();
  }

  private void refreshTrackingDisabled() {
    long now = System.currentTimeMillis();
    if (now - lastTrackingRefreshMs < 60_000L) return;
    lastTrackingRefreshMs = now;

    rest.select("tracking-refresh", "players",
        "select=id,live_tracking_enabled&live_tracking_enabled=eq.false")
        .whenComplete((rows, err) -> {
          if (err != null || rows == null) return;
          Set<UUID> nowDisabled = new HashSet<>();
          for (var el : rows) {
            try {
              nowDisabled.add(UUID.fromString(el.getAsJsonObject().get("id").getAsString()));
            } catch (Exception ignored) {
            }
          }
          // players who just opted out lose their row on the map
          for (UUID uid : nowDisabled) {
            if (!trackingDisabled.contains(uid)) deletePosition(uid);
          }
          trackingDisabled.retainAll(nowDisabled);
          trackingDisabled.addAll(nowDisabled);
        });
  }

  /** Covers players already connected when the plugin load task runs. */
  public void initOnline() {
    for (Player p : Bukkit.getOnlinePlayers()) {
      lastWriteMs.put(p.getUniqueId(), 0L);
    }
    refreshTrackingDisabled();
  }

  /** Removes every online player's marker in one request on plugin disable. */
  public void shutdown() {
    StringBuilder ids = new StringBuilder();
    for (Player p : Bukkit.getOnlinePlayers()) {
      if (ids.length() > 0) ids.append(',');
      ids.append(p.getUniqueId());
    }
    if (ids.length() == 0) return;
    rest.delete("live_positions", "live_positions", "player_id=in.(" + ids + ")")
        .exceptionally(err -> null);
  }
}