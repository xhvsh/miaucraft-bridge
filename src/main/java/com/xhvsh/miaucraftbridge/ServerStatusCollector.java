package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Keeps the single server_status_public row fresh (TPS, in-game day, start
 * time). The website treats anything older than 30s as "offline", so the
 * heartbeat interval must stay comfortably below that.
 */
public final class ServerStatusCollector {

  private static final long TICKS_PER_DAY = 24000L;

  private final SinkManager sinks;
  private final Supplier<RemoteConfig> config;
  private final String startedAtIso;

  public ServerStatusCollector(SinkManager sinks, Supplier<RemoteConfig> config) {
    this.sinks = sinks;
    this.config = config;
    this.startedAtIso = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()).toString();
  }

  public void report() {
    if (!config.get().collectorEnabled("status")) return;
    double[] tps = Bukkit.getTPS();
    JsonObject row = new JsonObject();
    row.addProperty("id", 1);
    row.addProperty("tps_1m", round(tps[0]));
    row.addProperty("tps_5m", round(tps[1]));
    row.addProperty("tps_15m", round(tps[2]));
    row.addProperty("days", dayCount());
    row.addProperty("started_at", startedAtIso);
    row.addProperty("updated_at", BridgeUtil.nowIso());
    sinks.sink("server_status_public", "id", true, "id").add(row);
  }

  private long dayCount() {
    List<World> worlds = Bukkit.getWorlds();
    if (worlds.isEmpty()) return 0L;
    return worlds.get(0).getFullTime() / TICKS_PER_DAY;
  }

  private double round(double v) {
    double clamped = Math.min(20.0, v);
    return Math.round(clamped * 100.0) / 100.0;
  }
}