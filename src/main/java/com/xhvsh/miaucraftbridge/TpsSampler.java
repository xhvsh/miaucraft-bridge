package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

import java.util.function.Supplier;

/**
 * Appends a periodic TPS + player-count sample to server_tps_samples so the
 * website can render a TPS history graph.  The table is append-only and the
 * sink flushes coalesced batches on the normal 5-second cycle.
 */
public final class TpsSampler {

  private final SinkManager sinks;
  private final Supplier<RemoteConfig> config;

  public TpsSampler(SinkManager sinks, Supplier<RemoteConfig> config) {
    this.sinks = sinks;
    this.config = config;
  }

  public void sample() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("tps")) return;
    double[] tps = Bukkit.getTPS();
    double tps1m = Math.max(0.0, Math.min(20.0, tps[0]));
    JsonObject row = new JsonObject();
    row.addProperty("sampled_at", BridgeUtil.nowIso());
    row.addProperty("tps", Math.round(tps1m * 10.0) / 10.0);
    row.addProperty("players_online", Bukkit.getOnlinePlayers().size());
    sinks.sink("server_tps_samples", "id", false, "id").add(row);
  }
}