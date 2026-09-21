package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.logging.Logger;

/**
 * Minimal PostgREST client with per-sink exponential backoff.
 *
 * A "sink" is a logical traffic lane (e.g. live_positions, chat_messages).
 * When a sink repeatedly fails, it is throttled independently so one broken
 * table can never turn into a reconnect storm for everything else.
 */
public class SupabaseRest {

  private final HttpClient http;
  private final String restBase;
  private final String apiKey;
  private final String schema;
  private final Logger log;
  private final Map<String, Backoff> backoffs = new ConcurrentHashMap<>();

  public static final class HttpError extends RuntimeException {
    public HttpError(String message) {
      super(message);
    }
  }

  private static final class Backoff {
    final long baseMs;
    int failures;
    long nextAllowedAtMs;

    Backoff(long baseMs) {
      this.baseMs = baseMs;
    }

    boolean blocked() {
      return System.currentTimeMillis() < nextAllowedAtMs;
    }

    void fail() {
      failures++;
      long delay = Math.min(baseMs * (1L << Math.min(failures - 1, 6)), 60000L);
      nextAllowedAtMs = System.currentTimeMillis() + delay;
    }

    void ok() {
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

  public boolean isBackedOff(String sink) {
    return backoffs.getOrDefault(sink, new Backoff(0)).blocked();
  }

  public int failures(String sink) {
    return backoffs.getOrDefault(sink, new Backoff(0)).failures;
  }

  public CompletableFuture<JsonArray> select(String sink, String table, String query) {
    return send(sink, "GET", restPath(table, query), null, "")
        .thenApply(el -> el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray());
  }

  public CompletableFuture<JsonElement> upsert(String sink, String table, JsonArray rows, String onConflict) {
    return send(sink, "POST", restPath(table, "on_conflict=" + encode(onConflict)), rows,
        "resolution=merge-duplicates,return=minimal");
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

  private String restPath(String table, String query) {
    return restBase + "/" + table + (query == null || query.isEmpty() ? "" : "?" + query);
  }

  private CompletableFuture<JsonElement> send(String sink, String method, String url,
      JsonElement body, String prefer) {
    Backoff backoff = backoffs.computeIfAbsent(sink, k -> new Backoff(500L));
    if (backoff.blocked()) {
      CompletableFuture<JsonElement> suppressed = new CompletableFuture<>();
      suppressed.completeExceptionally(new HttpError(sink + " suppressed by backoff (" + backoff.failures + " failures)"));
      return suppressed;
    }

    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .header("apikey", apiKey)
        .header("Authorization", "Bearer " + apiKey);
    if (!prefer.isEmpty()) builder.header("Prefer", prefer);
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
            if (backoff.failures == 1) {
              log.warning(sink + " " + method
                  + " failed HTTP " + code + " -> " + abbreviate(res.body()));
            }
            throw new HttpError(sink + " HTTP " + code);
          }
          backoff.ok();
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
          backoff.fail();
          if (backoff.failures == 1) {
            log.warning(sink + " " + method + " request error: " + cause);
          }
          if (cause instanceof HttpError) throw (HttpError) cause;
          throw new HttpError(sink + " transport failure: " + cause);
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