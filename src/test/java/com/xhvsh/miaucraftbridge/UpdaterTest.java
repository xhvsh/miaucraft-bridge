package com.xhvsh.miaucraftbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The updater refuses jars and swaps files, so its parsing and ordering rules are
 * the part that must not regress silently.
 */
class UpdaterTest {

  @Test
  void newerPatchIsGreater() {
    assertTrue(Updater.compareVersions("2.4.4", "2.4.3") > 0);
    assertTrue(Updater.compareVersions("2.5.0", "2.4.9") > 0);
    assertTrue(Updater.compareVersions("3.0.0", "2.99.99") > 0);
    assertEquals(0, Updater.compareVersions("2.4.3", "2.4.3"));
  }

  @Test
  void numericPartsCompareAsNumbersNotText() {
    // "10" > "9" numerically; a text compare would say otherwise.
    assertTrue(Updater.compareVersions("2.10.0", "2.9.0") > 0);
    assertTrue(Updater.compareVersions("1.0.10", "1.0.9") > 0);
  }

  @Test
  void preReleaseSortsBelowRelease() {
    assertTrue(Updater.compareVersions("2.4.3-SNAPSHOT", "2.4.3") < 0);
    assertTrue(Updater.compareVersions("2.4.3", "2.4.3-SNAPSHOT") > 0);
    assertTrue(Updater.compareVersions("2.4.4-SNAPSHOT", "2.4.3") > 0);
  }

  @Test
  void preReleaseIdentifiersOrderBySemverRules() {
    assertTrue(Updater.compareVersions("1.0.0-alpha.1", "1.0.0-alpha.2") < 0);
    assertTrue(Updater.compareVersions("1.0.0-alpha", "1.0.0-beta") < 0);
    assertTrue(Updater.compareVersions("1.0.0-alpha", "1.0.0-alpha.1") < 0);
    // Numeric identifiers always have lower precedence than alphanumeric ones.
    assertTrue(Updater.compareVersions("1.0.0-1", "1.0.0-alpha") < 0);
  }

  @Test
  void buildMetadataIsIgnored() {
    assertEquals(0, Updater.compareVersions("2.4.3+build.7", "2.4.3"));
    assertEquals(0, Updater.compareVersions("2.4.3+a", "2.4.3+b"));
    // ...and does not turn a release into a pre-release.
    assertTrue(Updater.compareVersions("2.4.3+meta", "2.4.3-SNAPSHOT") > 0);
  }

  @Test
  void missingPartsCountAsZero() {
    assertEquals(0, Updater.compareVersions("2.4", "2.4.0"));
    assertTrue(Updater.compareVersions("2.4.1", "2.4") > 0);
  }

  @Test
  void normalizeVersionTrimsLeadingV() {
    assertEquals("2.4.3", Updater.normalizeVersion("v2.4.3"));
    assertEquals("2.4.3", Updater.normalizeVersion("  2.4.3 "));
    assertNotNull(Updater.normalizeVersion("2.4.3-SNAPSHOT"));
  }

  @Test
  void normalizeVersionRejectsAnythingThatIsNotAVersion() {
    // The version becomes part of a file name inside the plugin data folder, so
    // traversal, separators, quoting and shell metacharacters are all refused.
    assertNull(Updater.normalizeVersion(null));
    assertNull(Updater.normalizeVersion(""));
    assertNull(Updater.normalizeVersion("latest"));
    assertNull(Updater.normalizeVersion("1/../../evil"));
    assertNull(Updater.normalizeVersion("1.0\\..\\x"));
    assertNull(Updater.normalizeVersion("2.4.3; rm -rf /"));
    assertNull(Updater.normalizeVersion("2.4.3\n3"));
    assertNull(Updater.normalizeVersion("-1.0"));
  }

  @Test
  void sha256MustBeExactly64HexChars() {
    assertTrue(Updater.isSha256("a".repeat(64)));
    assertTrue(Updater.isSha256("ED2689D86B482535225090F86F056A89B897B20D2A915C47E476011C7DB71E3E"));
    assertTrue(Updater.isSha256(null) == false);
    assertTrue(Updater.isSha256("") == false);
    assertTrue(Updater.isSha256("a".repeat(63)) == false);
    assertTrue(Updater.isSha256("a".repeat(65)) == false);
    assertTrue(Updater.isSha256("z".repeat(64)) == false);
  }
}
