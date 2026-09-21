package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * The GitHub-driven behavior config. Every server on every environment polls
 * the same URL, so editing bridge/remote-config.json and pushing to GitHub
 * reconfigures all of them without touching any plugin jar.
 *
 * Precedence (highest wins): local config.yml "overrides" > remote JSON >
 * hard-coded defaults (passed as method defaults).
 */
public final class RemoteConfig {

  private static final int SUPPORTED_VERSION = 1;

  private final JsonObject merged;
  private final Instant fetchedAt;
  private final String source;

  private RemoteConfig(JsonObject merged, String source) {
    this.merged = merged;
    this.fetchedAt = Instant.now();
    this.source = source;
  }

  public static RemoteConfig fromJson(JsonObject remote, JsonObject overrides) {
    JsonObject merged = deepMerge(remote, overrides);
    String source = "remote";
    if (remote == null || !remote.has("version")) {
      merged = overrides != null ? deepMerge(new JsonObject(), overrides) : new JsonObject();
      source = "overrides-only";
    }
    return new RemoteConfig(merged, source);
  }

  /**
   * Fetches the remote config. On any failure falls back to the last-good
   * cached copy, then to an empty config (collectors stay disabled).
   */
  public static CompletableFuture<RemoteConfig> fetch(String url, JsonObject overrides, Path cacheFile, Logger log) {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .header("User-Agent", "MiaucraftBridge/2.0")
        .GET()
        .build();

    return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
        .thenApply(res -> {
          if (res.statusCode() >= 300) {
            throw new RuntimeException("remote config HTTP " + res.statusCode());
          }
          JsonObject parsed = JsonParser.parseString(res.body()).getAsJsonObject();
          if (!parsed.has("version") || parsed.get("version").getAsInt() > SUPPORTED_VERSION) {
            log.warning("Remote config version not supported, falling back to cache/local.");
            return loadCache(cacheFile, overrides, log);
          }
          saveCache(cacheFile, parsed);
          RemoteConfig cfg = fromJson(parsed, overrides);
          String fetch = "Fetched remote config v"
              + parsed.get("version").getAsInt() + " from " + url;
          if (cfg.debugLog()) {
            log.info(fetch);
          } else {
            log.fine(fetch);
          }
          return cfg;
        })
        .exceptionally(err -> {
          log.warning("Remote config fetch failed (" + brief(err) + "), using cached copy.");
          return loadCache(cacheFile, overrides, log);
        });
  }

  private static RemoteConfig loadCache(Path cacheFile, JsonObject overrides, Logger log) {
    if (cacheFile != null && Files.exists(cacheFile)) {
      try {
        String text = Files.readString(cacheFile, StandardCharsets.UTF_8);
        JsonObject cached = JsonParser.parseString(text).getAsJsonObject();
        if (cached.has("version")) return fromJson(cached, overrides);
      } catch (Exception e) {
        log.warning("Could not read remote config cache: " + e.getMessage());
      }
    }
    return fromJson(null, overrides);
  }

  private static void saveCache(Path cacheFile, JsonObject parsed) {
    try {
      if (cacheFile == null) return;
      if (cacheFile.getParent() != null) Files.createDirectories(cacheFile.getParent());
      Files.writeString(cacheFile, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(parsed),
          StandardCharsets.UTF_8);
    } catch (Exception ignored) {
    }
  }

  public String source() {
    return source;
  }

  /** True when the remote "log.level" requests verbose (DEBUG/FINE+) logging. */
  public boolean debugLog() {
    String level = "";
    if (merged.has("log") && merged.get("log").isJsonObject()) {
      JsonElement el = merged.getAsJsonObject("log").get("level");
      if (el != null && el.isJsonPrimitive()) level = el.getAsString();
    }
    String lvl = level.toUpperCase(Locale.ROOT);
    return lvl.equals("DEBUG") || lvl.equals("TRACE") || lvl.equals("ALL")
        || lvl.equals("FINE") || lvl.equals("FINER") || lvl.equals("FINEST");
  }

  public int version() {
    return merged.has("version") ? merged.get("version").getAsInt() : 0;
  }

  public boolean collectorEnabled(String name) {
    // No remote config at all (not loaded yet, fetch failed with no cache, or
    // version 0) means every collector stays off. Better silent than writing
    // to a database we were never told to talk to.
    if (version() <= 0) return false;
    JsonObject c = collector(name);
    return (c == null || !c.has("enabled") || c.get("enabled").getAsBoolean()) && !isMasterDisabled();
  }

  private boolean isMasterDisabled() {
    return merged.has("enabled") && !merged.get("enabled").getAsBoolean();
  }

  public int collectorInt(String collector, String key, int def) {
    JsonObject c = collector(collector);
    return (c == null || !c.has(key)) ? def : c.get(key).getAsInt();
  }

  public long collectorLong(String collector, String key, long def) {
    JsonObject c = collector(collector);
    return (c == null || !c.has(key)) ? def : c.get(key).getAsLong();
  }

  public JsonArray collectorArray(String collector, String key, JsonArray def) {
    JsonObject c = collector(collector);
    JsonElement v = c == null ? null : c.get(key);
    if (v != null && v.isJsonArray()) return v.getAsJsonArray();
    return def;
  }

  /** e.g. arrayAt("collectors.stats.categories.UNTYPED", null) */
  public JsonArray arrayAt(String dotted, JsonArray def) {
    JsonElement el = at(dotted);
    return el != null && el.isJsonArray() ? el.getAsJsonArray() : def;
  }

  private JsonObject collector(String name) {
    JsonElement c = merged.get("collectors");
    if (c == null || !c.isJsonObject()) return null;
    JsonElement named = c.getAsJsonObject().get(name);
    return named != null && named.isJsonObject() ? named.getAsJsonObject() : null;
  }

  public int intVal(String dotted, int def) {
    JsonElement el = at(dotted);
    return el != null && el.isJsonPrimitive() ? el.getAsInt() : def;
  }

  public long longVal(String dotted, long def) {
    JsonElement el = at(dotted);
    return el != null && el.isJsonPrimitive() ? el.getAsLong() : def;
  }

  public boolean boolVal(String dotted, boolean def) {
    JsonElement el = at(dotted);
    return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()
        ? el.getAsBoolean() : def;
  }

  public String strVal(String dotted, String def) {
    JsonElement el = at(dotted);
    return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
        ? el.getAsString() : def;
  }

  public JsonObject objectPath(String dotted) {
    JsonElement el = at(dotted);
    return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
  }

  private JsonElement at(String dotted) {
    JsonElement cur = merged;
    for (String part : dotted.split("\\.")) {
      if (cur == null || !cur.isJsonObject()) return null;
      cur = cur.getAsJsonObject().get(part);
    }
    return cur;
  }

  /** Deep-merge overlay onto base; base is mutated and returned. */
  private static JsonObject deepMerge(JsonObject base, JsonObject overlay) {
    if (base == null) base = new JsonObject();
    if (overlay == null) return base;
    for (String key : overlay.keySet()) {
      JsonElement ov = overlay.get(key);
      JsonElement bv = base.get(key);
      if (ov != null && ov.isJsonObject() && bv != null && bv.isJsonObject()) {
        deepMerge(bv.getAsJsonObject(), ov.getAsJsonObject());
      } else {
        base.add(key, ov);
      }
    }
    return base;
  }

  private static String brief(Throwable err) {
    return err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage();
  }
}
