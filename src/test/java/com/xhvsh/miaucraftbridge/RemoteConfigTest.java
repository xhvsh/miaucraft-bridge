package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The remote document is untrusted input that decides what the collectors do, so
 * everything here is about refusing nonsense without throwing at the call site.
 */
class RemoteConfigTest {

  private static JsonObject json(String text) {
    return JsonParser.parseString(text).getAsJsonObject();
  }

  @Test
  void supportedVersionMustBeAnIntegerInRange() {
    assertTrue(RemoteConfig.isSupported(json("{\"version\":1}")));
    assertFalse(RemoteConfig.isSupported(null));
    assertFalse(RemoteConfig.isSupported(json("{}")));
    assertFalse(RemoteConfig.isSupported(json("{\"version\":\"1\"}")));
    assertFalse(RemoteConfig.isSupported(json("{\"version\":2}")));
    assertFalse(RemoteConfig.isSupported(json("{\"version\":0}")));
    // A number this build cannot reason about must not be treated as supported.
    assertFalse(RemoteConfig.isSupported(json("{\"version\":1e100}")));
    assertFalse(RemoteConfig.isSupported(json("{\"version\":true}")));
  }

  @Test
  void unsupportedDocumentFallsBackToOverridesOnly() {
    JsonObject overrides = json("{\"collectors\":{\"stats\":{\"enabled\":true,\"reconcile-minutes\":30}}}");
    RemoteConfig cfg = RemoteConfig.fromJson(json("{\"version\":99,\"collectors\":{\"stats\":{\"enabled\":false,"
        + "\"reconcile-minutes\":5}}}"), overrides);
    assertEquals("overrides-only", cfg.source());
    // Collectors stay off until a supported document has been seen once, so a
    // hostile or too-new config can never start writing.
    assertFalse(cfg.collectorEnabled("stats"));
    assertEquals(30L, cfg.collectorLong("stats", "reconcile-minutes", 15));
  }

  @Test
  void localOverridesWinOverRemote() {
    JsonObject remote = json("{\"version\":1,\"collectors\":{\"stats\":{\"enabled\":true,"
        + "\"reconcile-minutes\":5}}}");
    JsonObject overrides = json("{\"collectors\":{\"stats\":{\"reconcile-minutes\":30}}}");
    RemoteConfig cfg = RemoteConfig.fromJson(remote, overrides);
    assertEquals("remote", cfg.source());
    assertEquals(1, cfg.version());
    assertEquals(30L, cfg.collectorLong("stats", "reconcile-minutes", 15));
    assertTrue(cfg.collectorEnabled("stats"));
  }

  @Test
  void remoteMasterSwitchDisablesEverything() {
    RemoteConfig off = RemoteConfig.fromJson(
        json("{\"version\":1,\"enabled\":false,\"collectors\":{\"stats\":{\"enabled\":true}}}"), json("{}"));
    assertFalse(off.collectorEnabled("stats"));
    assertFalse(off.collectorEnabled("anything"));
  }

  @Test
  void localKillSwitchGatesEveryCollector() {
    RemoteConfig cfg = RemoteConfig.fromJson(
        json("{\"version\":1,\"collectors\":{\"stats\":{\"enabled\":true},\"chat\":{\"enabled\":true}}}"), json("{}"));
    AtomicBoolean gate = new AtomicBoolean(true);
    cfg.setMasterEnabled(gate::get);
    assertTrue(cfg.collectorEnabled("stats"));
    assertTrue(cfg.collectorEnabled("chat"));

    gate.set(false);
    assertFalse(cfg.collectorEnabled("stats"));
    assertFalse(cfg.collectorEnabled("chat"));
  }

  @Test
  void collectorsStayOffUntilASupportedConfigHasBeenSeen() {
    RemoteConfig cfg = RemoteConfig.fromJson(null, json("{\"collectors\":{\"stats\":{\"enabled\":true}}}"));
    assertFalse(cfg.collectorEnabled("stats"));
    cfg.setMasterEnabled(() -> true);
    assertFalse(cfg.collectorEnabled("stats"));
  }

  @Test
  void wronglyTypedValuesFallBackToTheDefault() {
    JsonObject remote = json("{\"version\":1,"
        + "\"status\":{\"heartbeat-seconds\":\"soon\"},"
        + "\"collectors\":{\"stats\":{\"reconcile-minutes\":true,\"categories\":{\"UNTYPED\":\"nope\"}}}}");
    RemoteConfig cfg = RemoteConfig.fromJson(remote, json("{}"));
    assertEquals(25L, cfg.longVal("status.heartbeat-seconds", 25));
    assertEquals(15L, cfg.collectorLong("stats", "reconcile-minutes", 15));
    assertEquals(7, cfg.intVal("nothing.here", 7));
    assertEquals("fallback", cfg.strVal("status.version", "fallback"));
    JsonArray missing = cfg.arrayAt("collectors.stats.categories.UNTYPED", null);
    assertTrue(missing == null || missing.size() == 0);
  }

  @Test
  void debugLogFollowsTheRemoteLogLevel() {
    assertFalse(RemoteConfig.fromJson(json("{\"version\":1}"), json("{}")).debugLog());
    assertFalse(RemoteConfig.fromJson(json("{\"version\":1,\"log\":{\"level\":\"INFO\"}}"), json("{}")).debugLog());
    assertTrue(RemoteConfig.fromJson(json("{\"version\":1,\"log\":{\"level\":\"DEBUG\"}}"), json("{}")).debugLog());
    assertTrue(RemoteConfig.fromJson(json("{\"version\":1,\"log\":{\"level\":\"debug\"}}"), json("{}")).debugLog());
  }

  @Test
  void generationIsCarriedForStaleFetchGuards() {
    RemoteConfig cfg = RemoteConfig.fromJson(json("{\"version\":1}"), json("{}"));
    assertEquals(0L, cfg.generation());
    assertTrue(cfg.withGeneration(7L) == cfg);
    assertEquals(7L, cfg.generation());
  }
}
