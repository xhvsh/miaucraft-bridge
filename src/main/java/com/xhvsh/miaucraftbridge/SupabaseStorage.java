package com.xhvsh.miaucraftbridge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Minimal Supabase Storage client used to publish map tiles. Tiles are written
 * with {@code x-upsert: true} (idempotent re-renders) and cache headers that
 * let the site's tile layer cache them for a year, so Leaflet reuses the CDN
 * edge instead of re-downloading on every visit.
 */
final class SupabaseStorage {

  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final String storageBase;
  private final String serviceKey;
  private final HttpClient http;
  private final Logger log;

  SupabaseStorage(String projectUrl, String serviceRoleKey, Logger log) {
    String base = projectUrl.endsWith("/") ? projectUrl.substring(0, projectUrl.length() - 1) : projectUrl;
    this.storageBase = base + "/storage/v1";
    this.serviceKey = serviceRoleKey;
    this.log = log;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  }

  /**
   * PUTs one tile. The service-role key is authoritative (read/write/delete on
   * any object), so tiles are only ever published from the server side, never
   * from the website. Throws on non-success after a single quiet retry.
   */
  void upload(String bucket, String key, byte[] data, String contentType) throws IOException {
    String url = storageBase + "/object/" + encodeSegment(bucket) + "/" + encodePath(key);
    IOException failure = null;
    for (int attempt = 1; attempt <= 2; attempt++) {
      try {
        int code = sendOnce(url, data, contentType);
        if (code >= 200 && code < 300) {
          return;
        }
        failure = new java.io.IOException("HTTP " + code + " " + key);
        if (code < 500) {
          break; // a rejected key will never succeed on retry
        }
      } catch (IOException e) {
        failure = e;
      } catch (Exception e) {
        failure = new java.io.IOException("request error " + key + ": " + e);
        break;
      }
      if (attempt == 1) {
        try {
          Thread.sleep(1000L);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    throw failure;
  }

  private int sendOnce(String url, byte[] data, String contentType) throws java.io.IOException {
    HttpRequest request;
    try {
      request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(TIMEOUT)
          .header("apikey", serviceKey)
          .header("Authorization", "Bearer " + serviceKey)
          .header("Content-Type", contentType)
          .header("x-upsert", "true")
          .header("cache-control", "public, max-age=31536000, immutable")
          .POST(HttpRequest.BodyPublishers.ofByteArray(data))
          .build();
    } catch (RuntimeException e) {
      throw new java.io.IOException("bad upload url: " + e);
    }
    try {
      HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() >= 300) {
        String body = res.body();
        if (body != null && body.length() > 300) body = body.substring(0, 300);
        log.warning("map tile upload failed HTTP " + res.statusCode() + ": " + body);
      }
      return res.statusCode();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new java.io.IOException("upload interrupted");
    }
  }

  /** Builds a tile object key, e.g. <basePath>/world/3/0_0.png. */
  static String key(String basePath, String world, int zoom, int x, int z) {
    String prefix = basePath == null || basePath.isBlank() ? "" : basePath.strip();
    StringBuilder sb = new StringBuilder();
    if (!prefix.isEmpty()) {
      sb.append(prefix.strip()).append('/');
    }
    sb.append(world).append('/').append(zoom).append('/').append(x).append('_').append(z).append(".png");
    return sb.toString();
  }

  /** Biggest common denominator so all keys stay consistent for dedupe. */
  static java.nio.file.Path localTilePath(java.nio.file.Path tilesRoot, String world, int zoom, int x, int z) {
    return tilesRoot.resolve(world).resolve(String.valueOf(zoom))
        .resolve(x + "_" + z + ".png");
  }

  private static String encodeSegment(String s) {
    return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String encodePath(String key) {
    String[] parts = key.split("/");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) sb.append('/');
      sb.append(encodeSegment(parts[i]));
    }
    return sb.toString();
  }
}