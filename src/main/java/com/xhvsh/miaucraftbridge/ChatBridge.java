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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridges in-game chat and the website's chat_messages table:
 *   - a player message is inserted as kind='server' (the website shows it live),
 *   - rows of kind='web' are polled (past a persisted created_at watermark)
 *     and broadcast to the console and, optionally, all players.
 *
 * <p>Replays are suppressed by remembering the ids that were already relayed,
 * not only by the timestamp watermark: two rows created in the same
 * microsecond used to hide each other (the strict ">" comparison skipped the
 * second row forever), and a clock skew between servers could re-deliver an
 * old batch.
 */
public final class ChatBridge {

  private static final int RELAY_ID_MEMORY = 500;
  private static final int DEFAULT_MAX_LENGTH = 500;

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
    String message = sanitize(PlainTextComponentSerializer.plainText().serialize(event.message()),
        cfg.collectorInt("chat", "max-length", DEFAULT_MAX_LENGTH));
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
    row.addProperty("message", sanitize(message, DEFAULT_MAX_LENGTH));
    sinks.sink("chat_messages", "id", false, "id").add(row);
  }

  /** Strips control characters and clips to the configured length. */
  static String sanitize(String raw, int maxLength) {
    if (raw == null) return "";
    int limit = maxLength > 0 ? maxLength : DEFAULT_MAX_LENGTH;
    StringBuilder sb = new StringBuilder(Math.min(raw.length(), limit));
    for (int i = 0; i < raw.length() && sb.length() < limit; i++) {
      char c = raw.charAt(i);
      if (c == '\n' || c == '\r' || c == '\t') {
        sb.append(' ');
      } else if (!Character.isISOControl(c)) {
        sb.append(c);
      }
    }
    return sb.toString().trim();
  }

  // ------------------------------------------------------------- web -> game

  /**
   * Polls for web messages past the persisted (created_at, id) cursor and
   * broadcasts on main.
   *
   * <p>The cursor is a keyset, not just a timestamp: with a strict
   * {@code created_at > watermark} filter, two rows that share a timestamp were
   * mutually exclusive - the second one was skipped and then hidden behind the
   * watermark for good, even though {@code order=created_at,id} had put it
   * behind the first. Re-reading the boundary timestamp is not enough either,
   * because the id memory would then eat the page limit and the rest of the
   * same-timestamp rows would never be reached.
   */
  public void poll() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("chat") || polling) return;
    polling = true;

    String watermark = state.getString("chat-watermark", null);
    String lastId = state.getString("chat-last-id", null);
    StringBuilder query = new StringBuilder(
        "select=id,username,message,created_at&kind=eq.web&order=created_at.asc,id.asc&limit=100");
    boolean cursor = watermark != null && !watermark.isBlank();
    if (cursor && lastId != null && !lastId.isBlank()) {
      query.append("&or=(created_at.gt.").append(SupabaseRest.encode(watermark))
          .append(",and(created_at.eq.").append(SupabaseRest.encode(watermark))
          .append(",id.gt.").append(SupabaseRest.encode(lastId)).append("))");
    } else if (cursor) {
      query.append("&created_at=gt.").append(SupabaseRest.encode(watermark));
    }

    rest.select("chat-messages", "chat_messages", query.toString())
        .whenComplete((rows, err) -> {
          polling = false;
          if (err != null || rows == null || rows.isEmpty()) return;
          Bukkit.getScheduler().runTask(plugin, () -> relay(rows));
        });
  }

  private void relay(JsonArray rows) {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("chat")) return;
    boolean relayToPlayers = cfg.boolVal("collectors.chat.relay-to-players", true);
    String prefix = cfg.strVal("collectors.chat.web-prefix", "[web] ");
    TextColor prefixColor = color(cfg.strVal("collectors.chat.web-prefix-color", "AQUA"), NamedTextColor.AQUA);
    String bodyFormat = cfg.strVal("collectors.chat.web-body-format", "<{username}> {message}");
    TextColor bodyColor = color(cfg.strVal("collectors.chat.web-body-color", "WHITE"), NamedTextColor.WHITE);

    Set<String> already = relayedIds();
    String newest = null;
    String newestId = null;
    int relayed = 0;
    for (JsonElement el : rows) {
      try {
        if (el == null || !el.isJsonObject()) continue;
        JsonObject row = el.getAsJsonObject();
        String id = row.has("id") && !row.get("id").isJsonNull() ? row.get("id").getAsString() : null;
        String created = row.has("created_at") && !row.get("created_at").isJsonNull()
            ? row.get("created_at").getAsString() : null;

        // The cursor follows the last row of the (already ordered) page, not the
        // last row that was actually relayed: a page of already-known ids must
        // still advance, otherwise it is fetched again on every poll forever.
        if (created != null && (newest == null || after(created, id, newest, newestId))) {
          newest = created;
          newestId = id;
        }

        if (id != null && already.contains(id)) continue;

        String username = row.has("username") && !row.get("username").isJsonNull()
            ? row.get("username").getAsString() : "someone";
        String message = row.has("message") && !row.get("message").isJsonNull()
            ? row.get("message").getAsString() : "";
        if (message.isBlank()) continue;
        String body = bodyFormat
            .replace("{username}", username)
            .replace("{message}", message);
        Component line = Component.text(prefix, prefixColor).append(Component.text(body, bodyColor));
        if (relayToPlayers) {
          Bukkit.getServer().sendMessage(line);
        } else {
          Bukkit.getConsoleSender().sendMessage(line);
        }
        relayed++;
        if (id != null) already.add(id);
      } catch (RuntimeException e) {
        // One malformed row must never stall the whole relay loop.
        log.log(Level.FINE, "Skipped malformed chat row", e);
      }
    }

    lastRelayed = relayed;
    synchronized (state) {
      if (newest != null) {
        state.set("chat-watermark", newest);
        if (newestId != null) {
          state.set("chat-last-id", newestId);
        } else {
          state.remove("chat-last-id");
        }
      }
      trimRelayedIds(already);
    }
    state.save();
  }

  /** Order by (created_at, id), the same order the query uses. */
  static boolean after(String created, String id, String newestCreated, String newestId) {
    int cmp = created.compareTo(newestCreated);
    if (cmp != 0) return cmp > 0;
    if (id == null) return false;
    if (newestId == null) return true;
    return id.compareTo(newestId) > 0;
  }

  private Set<String> relayedIds() {
    Set<String> set = new LinkedHashSet<>();
    var saved = state.object("chat-relayed-ids");
    for (String key : saved.keySet()) {
      if (saved.has(key)) set.add(key);
    }
    return set;
  }

  private void trimRelayedIds(Set<String> ids) {
    JsonObject target = state.object("chat-relayed-ids");
    int overflow = ids.size() - RELAY_ID_MEMORY;
    if (overflow > 0) {
      List<String> doomed = new ArrayList<>();
      for (String key : ids) {
        if (doomed.size() >= overflow) break;
        doomed.add(key);
      }
      ids.removeAll(doomed);
      for (String key : doomed) target.remove(key);
    }
    for (String key : ids) target.addProperty(key, true);
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

  /** Test/diagnostic helper: how many relayed ids are still remembered. */
  public int rememberedIds() {
    synchronized (state) {
      return new ArrayList<>(relayedIds()).size();
    }
  }
}
