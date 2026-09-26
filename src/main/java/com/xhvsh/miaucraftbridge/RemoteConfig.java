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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * The GitHub-driven behavior config. Every server on every environment polls
 * the same URL, so editing remote-config.json and pushing to GitHub
 * reconfigures all of them without touching any plugin jar.
 *
 * Precedence (highest wins): local config.yml "overrides" &gt; remote JSON &gt;
 * hard-coded defaults (passed as method defaults).
 *
 * <p>Every accessor is type-safe: a malformed remote value falls back to the
 * supplied default (and is logged once) instead of throwing inside an event
 * handler or a scheduler task.
 */
public final class RemoteConfig {

  private static final int SUPPORTED_VERSION = 1;

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private static final Logger SELF = Logger.getLogger("MiaucraftBridge");

  private final JsonObject merged;
  private final Instant fetchedAt;
  private final String source;
  private final Set<String> warned = ConcurrentHashMap.newKeySet();
  private volatile BooleanSupplier masterEnabled;
  private volatile long generation;

  private RemoteConfig(JsonObject merged, String source, BooleanSupplier masterEnabled) {
    this.merged = merged;
    this.fetchedAt = Instant.now();
    this.source = source;
    this.masterEnabled = masterEnabled == null ? () -> true : masterEnabled;
  }

  /**
   * The fetch generation this document belongs to. Two overlapping fetches (the
   * poll timer and /bridge reload) can complete out of order, so the caller
   * only applies a result whose generation is still the newest one.
   */
  public long generation() {
    return generation;
  }

  public RemoteConfig withGeneration(long generation) {
    this.generation = generation;
    return this;
  }

  public static RemoteConfig fromJson(JsonObject remote, JsonObject overrides) {
    return fromJson(remote, overrides, null);
  }

  public static RemoteConfig fromJson(JsonObject remote, JsonObject overrides, BooleanSupplier masterEnabled) {
    JsonObject merged = deepMerge(remote, overrides);
    String source = "remote";
    if (remote == null || !isSupported(remote)) {
      merged = overrides != null ? deepMerge(new JsonObject(), overrides) : new JsonObject();
      source = "overrides-only";
    }
    return new RemoteConfig(merged, source, masterEnabled);
  }

  /** True when the document carries a usable integer version this build understands. */
  static boolean isSupported(JsonObject doc) {
    if (doc == null) return false;
    JsonElement el = doc.get("version");
    if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return false;
    int version;
    try {
      version = el.getAsInt();
    } catch (NumberFormatException e) {
      // e.g. a huge or fractional number: not a version this build can reason about.
      return false;
    }
    return version >= 1 && version <= SUPPORTED_VERSION;
  }

  /** Wires the local master switch (config.yml "enabled") into every collector check. */
  public void setMasterEnabled(BooleanSupplier supplier) {
    this.masterEnabled = supplier == null ? () -> true : supplier;
  }

  /** raw.githubusercontent.com caches files; a query param forces a fresh edge fetch. */
  private static String bust(String url) {
    String t = String.valueOf(System.currentTimeMillis());
    return url.indexOf('?') >= 0 ? url + "&t=" + t : url + "?t=" + t;
  }

  /**
   * Fetches the remote config. On any failure falls back to the last-good
   * cached copy, then to the local overrides (collectors stay disabled until a
   * supported config has been seen).
   */
  public static CompletableFuture<RemoteConfig> fetch(String url, JsonObject overrides, Path cacheFile, Logger log) {
    return fetch(url, overrides, cacheFile, log, null);
  }

  public static CompletableFuture<RemoteConfig> fetch(String url, JsonObject overrides, Path cacheFile, Logger log,
      BooleanSupplier masterEnabled) {
    HttpRequest req;
    try {
      req = HttpRequest.newBuilder()
          .uri(URI.create(bust(url)))
          .timeout(Duration.ofSeconds(15))
          .header("User-Agent", "MiaucraftBridge/2.0")
          .GET()
          .build();
    } catch (RuntimeException e) {
      log.warning("Remote config url is invalid (" + e.getMessage() + "), using cached copy.");
      return CompletableFuture.completedFuture(fromJson(null, overrides, masterEnabled));
    }

    return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
        .thenApply(res -> {
          if (res.statusCode() >= 300) {
            throw new IllegalStateException("remote config HTTP " + res.statusCode());
          }
          JsonElement parsed = JsonParser.parseString(res.body());
          if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalStateException("remote config is not a JSON object");
          }
          JsonObject doc = parsed.getAsJsonObject();
          if (!isSupported(doc)) {
            log.warning("Remote config version not supported, falling back to cache/local.");
            return fromJson(null, overrides, masterEnabled);
          }
          saveCache(cacheFile, doc);
          RemoteConfig cfg = fromJson(doc, overrides, masterEnabled);
          String fetch = "Fetched remote config v"
              + doc.get("version").getAsInt() + " from " + url;
          if (cfg.debugLog()) {
            log.info(fetch);
          } else {
            log.fine(fetch);
          }
          return cfg;
        })
        .exceptionally(err -> {
          log.warning("Remote config fetch failed (" + brief(err) + "), using cached copy.");
          return loadCache(cacheFile, overrides, log, masterEnabled);
        });
  }

  private static RemoteConfig loadCache(Path cacheFile, JsonObject overrides, Logger log,
      BooleanSupplier masterEnabled) {
    if (cacheFile != null && Files.exists(cacheFile)) {
      try {
        String text = Files.readString(cacheFile, StandardCharsets.UTF_8);
        JsonElement parsed = JsonParser.parseString(text);
        if (parsed != null && parsed.isJsonObject()) {
          JsonObject cached = parsed.getAsJsonObject();
          if (isSupported(cached)) return fromJson(cached, overrides, masterEnabled);
        }
      } catch (Exception e) {
        log.warning("Could not read remote config cache: " + e.getMessage());
      }
    }
    return fromJson(null, overrides, masterEnabled);
  }

  private static void saveCache(Path cacheFile, JsonObject parsed) {
    if (cacheFile == null) return;
    try {
      if (cacheFile.getParent() != null && !Files.exists(cacheFile.getParent())) {
        Files.createDirectories(cacheFile.getParent());
      }
      Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
      Files.writeString(tmp, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(parsed),
          StandardCharsets.UTF_8);
      try {
        Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (Exception ignored) {
    }
  }

  public String source() {
    return source;
  }

  public Instant fetchedAt() {
    return fetchedAt;
  }

  /** True when the remote "log.level" requests verbose (DEBUG/FINE+) logging. */
  public boolean debugLog() {
    String lvl = strVal("log.level", "INFO").toUpperCase(Locale.ROOT);
    return lvl.equals("DEBUG") || lvl.equals("TRACE") || lvl.equals("ALL")
        || lvl.equals("FINE") || lvl.equals("FINER") || lvl.equals("FINEST");
  }

  public int version() {
    return intVal("version", 0);
  }

  public boolean collectorEnabled(String name) {
    if (version() <= 0) return false;
    if (!masterEnabled()) return false;
    JsonObject c = collector(name);
    return (c == null || !c.has("enabled") || boolOf(c, "enabled", true)) && !isMasterDisabled();
  }

  private boolean masterEnabled() {
    BooleanSupplier supplier = masterEnabled;
    return supplier == null || supplier.getAsBoolean();
  }

  private boolean isMasterDisabled() {
    JsonElement el = merged.get("enabled");
    return el != null && el.isJsonPrimitive() && !boolOf(merged, "enabled", true);
  }

  public int collectorInt(String collector, String key, int def) {
    JsonObject c = collector(collector);
    if (c == null) return def;
    return intOf(c, "collectors." + collector + "." + key, def);
  }

  public long collectorLong(String collector, String key, long def) {
    JsonObject c = collector(collector);
    if (c == null) return def;
    return longOf(c, "collectors." + collector + "." + key, def);
  }

  public JsonArray collectorArray(String collector, String key, JsonArray def) {
    JsonObject c = collector(collector);
    JsonElement v = c == null ? null : c.get(key);
    return v != null && v.isJsonArray() ? v.getAsJsonArray() : def;
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
    if (el == null || !el.isJsonPrimitive()) return def;
    try {
      return el.getAsInt();
    } catch (RuntimeException e) {
      warnType(dotted, "number", el);
      return def;
    }
  }

  public long longVal(String dotted, long def) {
    JsonElement el = at(dotted);
    if (el == null || !el.isJsonPrimitive()) return def;
    try {
      return el.getAsLong();
    } catch (RuntimeException e) {
      warnType(dotted, "number", el);
      return def;
    }
  }

  public boolean boolVal(String dotted, boolean def) {
    JsonElement el = at(dotted);
    if (el == null || !el.isJsonPrimitive()) return def;
    JsonPrimitive p = el.getAsJsonPrimitive();
    if (p.isBoolean()) return p.getAsBoolean();
    warnType(dotted, "boolean", el);
    return def;
  }

  public String strVal(String dotted, String def) {
    JsonElement el = at(dotted);
    if (el == null || !el.isJsonPrimitive()) return def;
    JsonPrimitive p = el.getAsJsonPrimitive();
    if (p.isString()) return p.getAsString();
    warnType(dotted, "string", el);
    return def;
  }

  public JsonObject objectPath(String dotted) {
    JsonElement el = at(dotted);
    return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
  }

  private int intOf(JsonObject parent, String path, int def) {
    JsonElement el = parent == null ? null : parent.get(lastSegment(path));
    if (el == null || !el.isJsonPrimitive()) return def;
    try {
      return el.getAsInt();
    } catch (RuntimeException e) {
      warnType(path, "number", el);
      return def;
    }
  }

  private long longOf(JsonObject parent, String path, long def) {
    JsonElement el = parent == null ? null : parent.get(lastSegment(path));
    if (el == null || !el.isJsonPrimitive()) return def;
    try {
      return el.getAsLong();
    } catch (RuntimeException e) {
      warnType(path, "number", el);
      return def;
    }
  }

  private boolean boolOf(JsonObject parent, String key, boolean def) {
    JsonElement el = parent == null ? null : parent.get(key);
    if (el == null || !el.isJsonPrimitive()) return def;
    JsonPrimitive p = el.getAsJsonPrimitive();
    if (p.isBoolean()) return p.getAsBoolean();
    warnType(key, "boolean", el);
    return def;
  }

  private static String lastSegment(String path) {
    int idx = path.lastIndexOf('.');
    return idx < 0 ? path : path.substring(idx + 1);
  }

  private void warnType(String path, String expected, JsonElement actual) {
    if (warned.add(path)) {
      SELF.warning("remote config key '" + path + "' should be a " + expected
          + " (got " + actual + "); using the built-in default");
    }
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
