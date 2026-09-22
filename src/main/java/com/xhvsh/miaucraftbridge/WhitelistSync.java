package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Two-way whitelist bridge:
 *   - mirror the server's whitelist up to Supabase (only rows that changed),
 *   - apply pending whitelist_commands rows requested from the website.
 *
 * The old plugin polled pending commands every 3s unconditionally; here the
 * poll interval is remote-config driven and the mirror only writes diffs.
 */
public final class WhitelistSync {

  private final Plugin plugin;
  private final SinkManager sinks;
  private final SupabaseRest rest;
  private final PersistedState state;
  private final Supplier<RemoteConfig> config;
  private final Logger log;

  private final JsonObject known;
  private volatile boolean polling = false;
  private volatile long lastMirrorMs = 0L;
  private volatile int lastPending = 0;

  public WhitelistSync(Plugin plugin, SinkManager sinks, SupabaseRest rest, PersistedState state,
      Supplier<RemoteConfig> config, Logger log) {
    this.plugin = plugin;
    this.sinks = sinks;
    this.rest = rest;
    this.state = state;
    this.config = config;
    this.log = log;
    this.known = state.object("whitelist");
  }

  public long lastMirrorMs() {
    return lastMirrorMs;
  }

  public int lastPending() {
    return lastPending;
  }

  /** Runs on the main thread (reads Bukkit whitelist state). */
  public void mirror() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("whitelist")) return;

    Map<String, String> current = new ConcurrentHashMap<>();
    for (OfflinePlayer op : Bukkit.getWhitelistedPlayers()) {
      if (op.getName() == null) continue;
      current.put(op.getUniqueId().toString(), op.getName());
    }

    Set<String> toDelete = new HashSet<>();
    synchronized (state) {
      for (String id : known.keySet()) {
        if (!current.containsKey(id)) toDelete.add(id);
      }
      for (Map.Entry<String, String> e : current.entrySet()) {
        JsonElement prev = known.get(e.getKey());
        if (prev == null || !prev.getAsString().equals(e.getValue())) {
          JsonObject row = new JsonObject();
          row.addProperty("id", e.getKey());
          row.addProperty("username", e.getValue());
          row.addProperty("synced_at", BridgeUtil.nowIso());
          sinks.sink("whitelist", "id", true, "id").add(row);
          known.addProperty(e.getKey(), e.getValue());
        }
      }
      for (String id : toDelete) known.remove(id);
    }

    for (String id : toDelete) {
      rest.delete("whitelist", "whitelist", "id=eq." + id).exceptionally(err -> null);
    }
    state.save();
    lastMirrorMs = System.currentTimeMillis();
  }

  /** Polls pending whitelist_commands; applies them on the main thread. */
  public void pollCommands() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("whitelist") || polling) return;
    polling = true;
    rest.select("whitelist-commands", "whitelist_commands",
        "select=id,action,username&status=eq.pending&order=requested_at.asc")
        .whenComplete((rows, err) -> {
          polling = false;
          if (err != null || rows == null || rows.isEmpty()) return;
          lastPending = rows.size();
          Bukkit.getScheduler().runTask(plugin, () -> applyAll(rows));
        });
  }

  private void applyAll(JsonArray rows) {
    for (JsonElement el : rows) {
      JsonObject row = el.getAsJsonObject();
      String id = row.get("id").getAsString();
      String action = row.get("action").getAsString();
      String username = row.get("username").getAsString();
      String command = ("remove".equals(action) ? "whitelist remove " : "whitelist add ") + username;
      boolean ok;
      try {
        ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
      } catch (Exception e) {
        ok = false;
        log.warning("Whitelist command '" + command + "' threw: " + e.getMessage());
      }
      JsonObject patch = new JsonObject();
      patch.addProperty("status", ok ? "done" : "failed");
      patch.addProperty("processed_at", BridgeUtil.nowIso());
      rest.update("whitelist-commands", "whitelist_commands", "id=eq." + id, patch)
          .exceptionally(err -> null);
    }
    mirror();
  }
}