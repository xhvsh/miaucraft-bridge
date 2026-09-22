package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Bridges in-game chat and the website's chat_messages table:
 *   - a player message is inserted as kind='server' (the website shows it live),
 *   - rows of kind='web' are polled (past a persisted created_at watermark)
 *     and broadcast to the console and, optionally, all players.
 *
 * System rows (server online/offline) are written by this class too, replacing
 * the client-side transition notices the website used to fake.
 */
public final class ChatBridge {

  private final Plugin plugin;
  private final SinkManager sinks;
  private final SupabaseRest rest;
  private final PersistedState state;
  private final Supplier<RemoteConfig> config;
  private final Logger log;

  private volatile boolean polling = false;
  private volatile int lastRelayed = 0;

  public ChatBridge(Plugin plugin, SinkManager sinks, SupabaseRest rest, PersistedState state,
      Supplier<RemoteConfig> config, Logger log) {
    this.plugin = plugin;
    this.sinks = sinks;
    this.rest = rest;
    this.state = state;
    this.config = config;
    this.log = log;
  }

  public int lastRelayed() {
    return lastRelayed;
  }

  // ------------------------------------------------------------- game -> web

  public void onPlayerChat(AsyncChatEvent event) {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("chat")) return;
    String message = PlainTextComponentSerializer.plainText().serialize(event.message());
    if (message.isBlank()) return;

    JsonObject row = new JsonObject();
    row.addProperty("kind", "server");
    row.addProperty("username", event.getPlayer().getName());
    row.addProperty("message", message);
    sinks.sink("chat_messages", "id", false, "id").add(row);
  }

  /** Writes a system notice row (server up/down). */
  public void notice(String message) {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("system-notices")) return;
    JsonObject row = new JsonObject();
    row.addProperty("kind", "system");
    row.addProperty("message", message);
    sinks.sink("chat_messages", "id", false, "id").add(row);
  }

  // ------------------------------------------------------------- web -> game

  /** Polls for web messages newer than the watermark; broadcasts on main. */
  public void poll() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("chat") || polling) return;
    polling = true;

    String watermark = state.getString("chat-watermark", null);
    StringBuilder query = new StringBuilder("select=id,username,message,created_at&kind=eq.web&order=created_at.asc&limit=100");
    if (watermark != null && !watermark.isBlank()) {
      query.append("&created_at=gt.").append(SupabaseRest.encode(watermark));
    }

    rest.select("chat-messages", "chat_messages", query.toString())
        .whenComplete((rows, err) -> {
          polling = false;
          if (err != null || rows == null || rows.isEmpty()) return;
          lastRelayed = rows.size();
          Bukkit.getScheduler().runTask(plugin, () -> relay(rows));
        });
  }

  private void relay(JsonArray rows) {
    RemoteConfig cfg = config.get();
    boolean relayToPlayers = cfg.boolVal("collectors.chat.relay-to-players", true);
    String prefix = cfg.strVal("collectors.chat.web-prefix", "[web] ");
    TextColor prefixColor = color(cfg.strVal("collectors.chat.web-prefix-color", "AQUA"), NamedTextColor.AQUA);
    String bodyFormat = cfg.strVal("collectors.chat.web-body-format", "<{username}> {message}");
    TextColor bodyColor = color(cfg.strVal("collectors.chat.web-body-color", "WHITE"), NamedTextColor.WHITE);
    String newest = null;
    for (JsonElement el : rows) {
      JsonObject row = el.getAsJsonObject();
      String username = row.has("username") && !row.get("username").isJsonNull()
          ? row.get("username").getAsString() : "someone";
      String message = row.has("message") && !row.get("message").isJsonNull()
          ? row.get("message").getAsString() : "";
      if (row.has("created_at") && !row.get("created_at").isJsonNull()) {
        newest = row.get("created_at").getAsString();
      }
      String body = bodyFormat.replace("{username}", username).replace("{message}", message);
      Component line = Component.text(prefix, prefixColor)
          .append(Component.text(body, bodyColor));
      if (relayToPlayers) {
        Bukkit.getServer().sendMessage(line);
      } else {
        Bukkit.getConsoleSender().sendMessage(line);
      }
    }
    if (newest != null) {
      state.set("chat-watermark", newest);
      state.save();
    }
  }

  /** Accepts a named colour ("aqua", "dark_gray") or hex ("#55ffdd"). */
  private static TextColor color(String spec, TextColor fallback) {
    if (spec == null || spec.isBlank()) return fallback;
    if (spec.charAt(0) == '#') {
      TextColor hex = TextColor.fromHexString(spec);
      return hex != null ? hex : fallback;
    }
    NamedTextColor named = NamedTextColor.NAMES.value(spec.toLowerCase(Locale.ROOT).replace(' ', '_'));
    return named != null ? named : fallback;
  }
}