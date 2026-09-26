package com.xhvsh.miaucraftbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/**
 * Small disk-persisted JSON document used to carry diff caches and watermarks
 * across restarts, so a plugin reload never re-forces a full history flush
 * (the "restart burst" bug the old plugin had).
 *
 * Writes go to a sibling temp file that is then moved into place, so a crash
 * mid-write can never leave a truncated (and therefore cache-resetting) file.
 */
public final class PersistedState {

  private final Path file;
  private final Logger log;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private JsonObject root = new JsonObject();

  public PersistedState(Path file, Logger log) {
    this.file = file;
    this.log = log;
    load();
  }

  public synchronized JsonObject root() {
    return root;
  }

  public synchronized JsonObject object(String key) {
    JsonElement el = root.get(key);
    if (el != null && el.isJsonObject()) return el.getAsJsonObject();
    JsonObject fresh = new JsonObject();
    root.add(key, fresh);
    return fresh;
  }

  public synchronized boolean getBoolean(String key, boolean def) {
    JsonElement el = root.get(key);
    return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() ? el.getAsBoolean() : def;
  }

  public synchronized String getString(String key, String def) {
    JsonElement el = root.get(key);
    if (el == null || !el.isJsonPrimitive()) return def;
    try {
      return el.getAsString();
    } catch (RuntimeException e) {
      return def;
    }
  }

  public synchronized void set(String key, boolean value) {
    root.addProperty(key, value);
  }

  public synchronized void set(String key, String value) {
    root.addProperty(key, value);
  }

  public synchronized void set(String key, long value) {
    root.addProperty(key, value);
  }

  public synchronized void remove(String key) {
    root.remove(key);
  }

  private void load() {
    if (!Files.exists(file)) return;
    try {
      String text = Files.readString(file, StandardCharsets.UTF_8);
      JsonElement parsed = JsonParser.parseString(text);
      if (parsed != null && parsed.isJsonObject()) root = parsed.getAsJsonObject();
    } catch (Exception e) {
      log.warning("Could not read state file " + file + ": " + e.getMessage());
    }
  }

  public synchronized void save() {
    try {
      if (file.getParent() != null && !Files.exists(file.getParent())) {
        Files.createDirectories(file.getParent());
      }
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, gson.toJson(root), StandardCharsets.UTF_8);
      try {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException | RuntimeException e) {
      // A skipped save is harmless, a crashed server is not.
      log.warning("Could not save state file: " + e.getMessage());
    }
  }
}
