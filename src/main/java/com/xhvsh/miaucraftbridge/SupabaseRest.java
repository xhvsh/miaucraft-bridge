package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Minimal PostgREST client with per-sink exponential backoff.
 *
 * <p>A "sink" is a logical traffic lane (e.g. live_positions, chat_messages).
 * When a sink repeatedly fails, it is throttled independently so one broken
 * table can never turn into a reconnect storm for everything else.
 *
 * <p>Every request carries {@code Content-Type: application/json} and every
 * upsert carries {@code missing=default}, so columns the plugin does not write
 * keep their column DEFAULT instead of being inserted as NULL (a NOT NULL
 * column such as {@code players.hidden} would otherwise fail the insert).
 */
public class SupabaseRest {

  /** JSON body content type required by PostgREST for POST/PATCH payloads. */
  public static final String JSON = "application/json";

  private final HttpClient http;
  private final String restBase;
  private final String apiKey;
  private final String schema;
  private final Logger log;
  private final Map<String, Backoff> backoffs = new ConcurrentHashMap<>();
  private final Map<String, String> lastErrors = new ConcurrentHashMap<>();
  private volatile BooleanSupplier enabled = () -> true;

  public static final class HttpError extends RuntimeException {
    /** true when this error already advanced the backoff, so it must not fail twice. */
    public final boolean counted;

    public HttpError(String message) {
      this(message, false);
    }

    public HttpError(String message, boolean counted) {
      super(message);
      this.counted = counted;
    }
  }

  private static final class Backoff {
    final long baseMs;
    int failures;
    long nextAllowedAtMs;

    Backoff(long baseMs) {
      this.baseMs = baseMs;
    }

    synchronized boolean blocked() {
      return System.currentTimeMillis() < nextAllowedAtMs;
    }

    synchronized int failureCount() {
      return failures;
    }

    synchronized void fail() {
      failures++;
      long delay = Math.min(baseMs * (1L << Math.min(failures - 1, 6)), 60000L);
      nextAllowedAtMs = System.currentTimeMillis() + delay;
    }

    synchronized void ok() {
      failures = 0;
      nextAllowedAtMs = 0L;
    }
  }

  public SupabaseRest(String projectUrl, String serviceRoleKey, String schema, Logger log) {
    String base = projectUrl.endsWith("/") ? projectUrl.substring(0, projectUrl.length() - 1) : projectUrl;
    this.restBase = base + "/rest/v1";
    this.apiKey = serviceRoleKey;
    this.schema = (schema == null || schema.isBlank()) ? "public" : schema;
    this.log = log;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  /**
   * Global kill switch. When the supplier returns false every request - queued
   * sink traffic, direct reads, deletes and admin diagnostics alike - is
   * refused locally without touching the network.
   */
  public void setEnabled(BooleanSupplier supplier) {
    this.enabled = supplier == null ? () -> true : supplier;
  }

  public boolean isEnabled() {
    return enabled.getAsBoolean();
  }

  public boolean isBackedOff(String sink) {
    Backoff backoff = backoffs.get(sink);
    return backoff != null && backoff.blocked();
  }

  public int failures(String sink) {
    Backoff backoff = backoffs.get(sink);
    return backoff == null ? 0 : backoff.failureCount();
  }

  public String lastFailure(String sink) {
    return lastErrors.get(sink);
  }

  private static <T> CompletableFuture<T> rejected(String message) {
    CompletableFuture<T> failed = new CompletableFuture<>();
    failed.completeExceptionally(new HttpError(message));
    return failed;
  }

  public CompletableFuture<JsonArray> select(String sink, String table, String query) {
    return send(sink, "GET", restPath(table, query), null, "")
        .thenApply(el -> el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray());
  }

  public CompletableFuture<JsonElement> upsert(String sink, String table, JsonArray rows, String onConflict) {
    return send(sink, "POST", restPath(table, "on_conflict=" + encode(onConflict)), rows,
        "resolution=merge-duplicates,missing=default,return=minimal");
  }

  public CompletableFuture<JsonElement> insert(String sink, String table, JsonArray rows) {
    return send(sink, "POST", restPath(table, null), rows, "return=minimal");
  }

  public CompletableFuture<JsonElement> update(String sink, String table, String query, JsonElement patch) {
    return send(sink, "PATCH", restPath(table, query), patch, "return=minimal");
  }

  public CompletableFuture<JsonElement> delete(String sink, String table, String query) {
    return send(sink, "DELETE", restPath(table, query), null, "return=minimal");
  }

  /**
   * One-shot request that reports the raw status code + body instead of
   * backoff-ing, for admin diagnostics and conditional claims that need
   * {@code Prefer: return=representation}.
   */
  public CompletableFuture<RawResult> raw(String table, String method, String query,
      JsonElement body, String prefer) {
    if (!isEnabled()) {
      return CompletableFuture.completedFuture(new RawResult(0, "", "bridge disabled by config"));
    }
    HttpRequest.Builder builder;
    try {
      builder = HttpRequest.newBuilder()
          .uri(URI.create(restPath(table, query)))
          .timeout(Duration.ofSeconds(20))
          .header("apikey", apiKey)
          .header("Authorization", "Bearer " + apiKey);
    } catch (RuntimeException e) {
      return CompletableFuture.completedFuture(new RawResult(0, "", "bad request url: " + e.getMessage()));
    }
    if (prefer != null && !prefer.isEmpty()) builder.header("Prefer", prefer);
    if (body != null) builder.header("Content-Type", JSON);
    if ("GET".equals(method) || "DELETE".equals(method)) {
      builder.header("Accept-Profile", schema);
    } else {
      builder.header("Content-Profile", schema);
    }
    switch (method) {
      case "POST" -> builder.POST(body(body));
      case "PATCH" -> builder.method("PATCH", body(body));
      case "DELETE" -> builder.DELETE();
      default -> builder.GET();
    }
    return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
        .thenApply(res -> new RawResult(res.statusCode(), res.body(), null))
        .exceptionally(ex -> new RawResult(0, "",
            String.valueOf(ex.getCause() != null ? ex.getCause() : ex)));
  }

  /** One-shot upsert that reports the raw status code + body instead of throwing or backoff-ing. */
  public CompletableFuture<RawResult> rawUpsert(String table, JsonArray rows, String onConflict) {
    return raw(table, "POST", "on_conflict=" + encode(onConflict), rows,
        "resolution=merge-duplicates,missing=default,return=minimal");
  }

  /**
   * Calls a PostgREST function ({@code rpc/<name>}) and reports the raw HTTP
   * result, for maintenance calls like the retention purge that must not be
   * throttled by a sink's backoff.
   */
  public CompletableFuture<RawResult> rpc(String name, JsonElement params) {
    return raw("rpc/" + name, "POST", "", params == null ? new JsonObject() : params, "return=minimal");
  }

  public static final class RawResult {
    public final int code;
    public final String body;
    public final String error;

    RawResult(int code, String body, String error) {
      this.code = code;
      this.body = body;
      this.error = error;
    }

    public boolean ok() {
      return error == null && code < 300;
    }
  }

  private String restPath(String table, String query) {
    return restBase + "/" + table + (query == null || query.isEmpty() ? "" : "?" + query);
  }

  private CompletableFuture<JsonElement> send(String sink, String method, String url,
      JsonElement body, String prefer) {
    if (!isEnabled()) return rejected(sink + " suppressed: bridge disabled by config");
    Backoff backoff = backoffs.computeIfAbsent(sink, k -> new Backoff(500L));
    if (backoff.blocked()) {
      return rejected(sink + " suppressed by backoff (" + backoff.failureCount() + " failures)");
    }

    HttpRequest.Builder builder;
    try {
      builder = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(Duration.ofSeconds(15))
          .header("apikey", apiKey)
          .header("Authorization", "Bearer " + apiKey);
    } catch (RuntimeException e) {
      return rejected(sink + " bad request url: " + e.getMessage());
    }
    if (!prefer.isEmpty()) builder.header("Prefer", prefer);
    if (body != null) builder.header("Content-Type", JSON);
    if (method.equals("GET") || method.equals("DELETE")) {
      builder.header("Accept-Profile", schema);
    } else {
      builder.header("Content-Profile", schema);
    }
    switch (method) {
      case "POST" -> builder.POST(body(body));
      case "PATCH" -> builder.method("PATCH", body(body));
      case "DELETE" -> builder.DELETE();
      default -> builder.GET();
    }

    return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
        .thenApply(res -> {
          int code = res.statusCode();
          if (code >= 300) {
            backoff.fail();
            lastErrors.put(sink, "HTTP " + code + ": " + abbreviate(res.body()));
            if (backoff.failureCount() == 1) {
              log.warning(sink + " " + method
                  + " failed HTTP " + code + " -> " + abbreviate(res.body()));
            }
            // counted=true: exceptionally() must not fail the backoff again.
            throw new HttpError(sink + " HTTP " + code, true);
          }
          backoff.ok();
          lastErrors.remove(sink);
          String raw = res.body();
          if (raw == null || raw.isBlank()) return null;
          try {
            return JsonParser.parseString(raw);
          } catch (Exception ignored) {
            return null;
          }
        })
        .exceptionally(err -> {
          Throwable cause = err.getCause() != null ? err.getCause() : err;
          if (cause instanceof HttpError httpError) {
            // Already accounted for (or a backoff/disabled rejection): keep the
            // original message and never double-count the failure.
            throw httpError;
          }
          backoff.fail();
          lastErrors.put(sink, "transport: " + cause);
          if (backoff.failureCount() == 1) {
            log.warning(sink + " " + method + " request error: " + cause);
          }
          throw new HttpError(sink + " transport failure: " + cause, true);
        });
  }

  private HttpRequest.BodyPublisher body(JsonElement el) {
    return el == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(el.toString());
  }

  public static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String abbreviate(String s) {
    if (s == null) return "";
    return s.length() <= 200 ? s : s.substring(0, 200) + "...";
  }
}
