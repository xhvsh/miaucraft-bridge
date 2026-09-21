package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Writes the players table (online flag, last_seen, afk) on join/quit and on
 * a low-frequency heartbeat. The heartbeat writes are coalesced by the sink,
 * so a player idles for hours with exactly one row written (the join row has
 * to exist anyway for the FK that live_positions/player_stats reference).
 */
public final class PresenceTracker implements Listener {

  private final SinkManager sinks;
  private final Supplier<RemoteConfig> config;
  private final ChatBridge chat;
  private final Map<UUID, Long> lastHeartbeat = new ConcurrentHashMap<>();
  private final Map<UUID, Long> lastMoved = new ConcurrentHashMap<>();

  public PresenceTracker(SinkManager sinks, Supplier<RemoteConfig> config, ChatBridge chat) {
    this.sinks = sinks;
    this.config = config;
    this.chat = chat;
  }

  private boolean enabled() {
    return config.get().collectorEnabled("presence");
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent e) {
    lastMoved.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    chat.notice(e.getPlayer().getName() + " joined the server");
    if (!enabled()) return;
    write(e.getPlayer(), true, false);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent e) {
    lastHeartbeat.remove(e.getPlayer().getUniqueId());
    lastMoved.remove(e.getPlayer().getUniqueId());
    chat.notice(e.getPlayer().getName() + " left the server");
    if (!enabled()) return;
    write(e.getPlayer(), false, false);
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onMove(PlayerMoveEvent e) {
    lastMoved.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
  }

  private void write(Player p, boolean online, boolean afk) {
    JsonObject row = new JsonObject();
    row.addProperty("id", p.getUniqueId().toString());
    row.addProperty("username", p.getName());
    row.addProperty("online", online);
    row.addProperty("last_seen", BridgeUtil.nowIso());
    row.addProperty("afk", afk);
    if (online) row.addProperty("last_moved", BridgeUtil.nowIso());
    sinks.sink("players", "id", true, "id").add(row);
  }

  /** Periodic low-frequency heartbeat so long-idle sessions stay "fresh". */
  public void heartbeatTick() {
    if (!enabled()) return;
    RemoteConfig cfg = config.get();
    long heartbeatMs = cfg.collectorLong("presence", "heartbeat-seconds", 300) * 1000L;
    long afkThresholdMs = cfg.collectorLong("presence", "afk-threshold-seconds", 300) * 1000L;
    long now = System.currentTimeMillis();
    for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
      UUID uid = p.getUniqueId();
      Long last = lastHeartbeat.get(uid);
      if (last != null && now - last < heartbeatMs) continue;
      lastHeartbeat.put(uid, now);
      Long moved = lastMoved.get(uid);
      boolean afk = moved != null && now - moved > afkThresholdMs;
      write(p, true, afk);
    }
  }

  /** Covers players already connected when the plugin load task runs. */
  public void initOnline() {
    long now = System.currentTimeMillis();
    for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
      lastMoved.put(p.getUniqueId(), now);
      lastHeartbeat.put(p.getUniqueId(), now - 61_000L);
      write(p, true, false);
    }
  }

  /** Marks everyone offline on plugin disable (e.g. /reload, where no quit fires). */
  public void shutdown() {
    if (!enabled()) return;
    for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
      write(p, false, false);
    }
  }
}