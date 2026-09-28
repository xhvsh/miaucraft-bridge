package com.xhvsh.miaucraftbridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two safety properties of the owner-facing operations surface.
 *
 * The allowlist has to hold on both sides: the database rejects a command
 * outside the list with a CHECK, and the plugin dispatches with a switch. If
 * those two ever disagree the feature breaks in a confusing way, so the list is
 * checked against the migration text directly.
 *
 * The console mirror must not be able to amplify an outage, which is what the
 * rate window prevents.
 */
class BridgeOpsTest {

  private static final Path MIGRATION =
      Path.of("dev", "supabase", "migrations", "20260101000400_bridge_ops.sql");

  /** Pulls the quoted values out of the bridge_commands command CHECK. */
  private static Set<String> allowlistFromMigration() throws IOException {
    assertTrue(Files.exists(MIGRATION),
        "expected the bridge operations migration at " + MIGRATION.toAbsolutePath());
    String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

    Matcher block = Pattern
        .compile("command\\s+text\\s+not null check \\(command in \\((.*?)\\)\\s*\\)", Pattern.DOTALL)
        .matcher(sql);
    assertTrue(block.find(), "could not find the command CHECK constraint in the migration");

    Set<String> found = new LinkedHashSet<>();
    Matcher value = Pattern.compile("'([^']+)'").matcher(block.group(1));
    while (value.find()) found.add(value.group(1));
    return found;
  }

  @Test
  void pluginAllowlistMatchesTheDatabaseCheck() throws IOException {
    assertEquals(allowlistFromMigration(), new LinkedHashSet<>(BridgeOps.COMMANDS),
        "the plugin command list and the migration CHECK have drifted apart");
  }

  @Test
  void allowlistHasNoDuplicates() {
    assertEquals(BridgeOps.COMMANDS.size(), new LinkedHashSet<>(BridgeOps.COMMANDS).size());
  }

  @Test
  void allowlistNeverCarriesAConsumerCommand() {
    for (String command : BridgeOps.COMMANDS) {
      assertTrue(command.matches("[a-z]+\\.[a-z]+"),
          "command should be a dotted <subject>.<action> symbol, not a command line: " + command);
    }
  }

  @Test
  void rateWindowKeepsTheFirstMessageAndDropsTheRest() {
    RateWindow window = new RateWindow(60_000L, 100, 1000);
    assertTrue(window.accept("error|chat_messages: HTTP 401"));
    assertFalse(window.accept("error|chat_messages: HTTP 401"),
        "a repeat of the same failure must not be stored again");
    assertFalse(window.accept("error|chat_messages: HTTP 401"));
    assertTrue(window.accept("error|server_status_public: HTTP 401"),
        "a different message is still worth keeping");
    assertEquals(2L, window.suppressed());
    assertEquals(2, window.acceptedInWindow());
  }

  @Test
  void rateWindowBoundsRowsPerWindow() {
    RateWindow window = new RateWindow(60_000L, 100, 3);
    for (int i = 0; i < 3; i++) {
      assertTrue(window.accept("info|distinct " + i));
    }
    assertFalse(window.accept("info|distinct 3"),
        "the per-window cap must hold even for distinct messages");
    assertEquals(1L, window.suppressed());
  }

  @Test
  void rateWindowCapsItsKeySet() {
    RateWindow window = new RateWindow(60_000L, 4, 1000);
    for (int i = 0; i < 20; i++) window.accept("info|line " + i);
    assertEquals(20, window.acceptedInWindow(),
        "clearing the key set must not lose the per-window row count");
  }

  @Test
  void trackedSinksCoverEveryTableThePluginWrites() {
    List<String> expected = new ArrayList<>(List.of(
        "players", "player_stats", "player_achievements", "player_achievement_criteria",
        "achievements", "achievement_criteria", "live_positions", "chat_messages",
        "whitelist", "server_status_public", "server_tps_samples",
        "bridge_events", "bridge_console"));
    assertEquals(new LinkedHashSet<>(expected), BridgeOps.TRACKED_SINKS);
  }

  @Test
  void stripColorsRemovesSectionCodes() {
    assertEquals("[MiaucraftBridge] done",
        CapturingSender.stripColors("\u00a7a[MiaucraftBridge] \u00a7fdone"));
    assertEquals("no codes here", CapturingSender.stripColors("no codes here"));
  }
}
