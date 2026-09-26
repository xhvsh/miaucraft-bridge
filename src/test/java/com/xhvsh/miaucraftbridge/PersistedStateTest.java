package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistedStateTest {

  private static final Logger LOG = Logger.getLogger("test");

  @Test
  void roundTripsThroughAnAtomicWrite(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("nested").resolve("bridge-state.json");
    PersistedState state = new PersistedState(file, LOG);
    state.set("chat-watermark", "2026-01-01T00:00:00Z");
    state.object("stats").addProperty("abc|MINE_BLOCK:STONE", 12L);
    state.save();

    assertTrue(Files.exists(file), "the parent directory is created on demand");
    // No temp file may be left behind.
    assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));

    PersistedState reloaded = new PersistedState(file, LOG);
    assertEquals("2026-01-01T00:00:00Z", reloaded.getString("chat-watermark", null));
    assertEquals(12L, reloaded.object("stats").get("abc|MINE_BLOCK:STONE").getAsLong());
  }

  @Test
  void aTruncatedStateFileDoesNotWipeTheRunningSession(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("bridge-state.json");
    Files.writeString(file, "{\"chat-watermark\": \"20", StandardCharsets.UTF_8);
    PersistedState state = new PersistedState(file, LOG);
    // The bad file is ignored, not thrown, and the state stays usable.
    state.set("chat-watermark", "2026-02-02T00:00:00Z");
    assertEquals("2026-02-02T00:00:00Z", state.getString("chat-watermark", null));
  }

  @Test
  void wrongTypesReturnTheDefault(@TempDir Path dir) {
    PersistedState state = new PersistedState(dir.resolve("s.json"), LOG);
    state.object("nested").addProperty("notAString", 5L);
    assertEquals("fallback", state.getString("missing", "fallback"));
    assertEquals("fallback", state.getString("nested", "fallback"));
    assertTrue(state.getBoolean("missing", true));
    assertFalse(state.getBoolean("nested", false));
  }

  @Test
  void removeDropsTheKey(@TempDir Path dir) {
    PersistedState state = new PersistedState(dir.resolve("s.json"), LOG);
    state.set("chat-last-id", "abc");
    state.remove("chat-last-id");
    assertEquals("gone", state.getString("chat-last-id", "gone"));
  }
}
