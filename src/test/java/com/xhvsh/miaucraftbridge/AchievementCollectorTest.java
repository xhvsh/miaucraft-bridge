package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offline scan reads Minecraft's own save files, whose criterion value
 * shape has changed between versions. These are the shapes that must keep
 * parsing without a live server.
 */
class AchievementCollectorTest {

  @Test
  void readsEpochMillisFromEveryKnownShape() {
    assertEquals(1700000000000L, AchievementCollector.readCriterionTimestamp(
        JsonParser.parseString("1700000000000")));
    assertEquals(1700000000000L, AchievementCollector.readCriterionTimestamp(
        JsonParser.parseString("\"1700000000000\"")));
    assertEquals(1700000000000L, AchievementCollector.readCriterionTimestamp(
        JsonParser.parseString("{\"time\":1700000000000}")));
    assertEquals(1700000000000L, AchievementCollector.readCriterionTimestamp(
        JsonParser.parseString("{\"ts\":\"1700000000000\"}")));
    assertEquals(1700000000000L, AchievementCollector.readCriterionTimestamp(
        JsonParser.parseString("{\"done_at\":\"2023-11-14 22:13:20\"}")));
  }

  @Test
  void unreadableValuesReturnZeroInsteadOfThrowing() {
    assertEquals(0L, AchievementCollector.readCriterionTimestamp(null));
    assertEquals(0L, AchievementCollector.readCriterionTimestamp(JsonParser.parseString("\"nope\"")));
    assertEquals(0L, AchievementCollector.readCriterionTimestamp(JsonParser.parseString("[]")));
    assertEquals(0L, AchievementCollector.readCriterionTimestamp(JsonParser.parseString("{}")));
    assertEquals(0L, AchievementCollector.readCriterionTimestamp(JsonParser.parseString("true")));
  }

  @Test
  void truthyCriterionShapes() {
    assertTrue(AchievementCollector.isCriterionTruthy(JsonParser.parseString("true")));
    assertTrue(AchievementCollector.isCriterionTruthy(JsonParser.parseString("1")));
    assertTrue(AchievementCollector.isCriterionTruthy(JsonParser.parseString("\"yes\"")));
    assertTrue(AchievementCollector.isCriterionTruthy(JsonParser.parseString("\"TRUE\"")));
    assertFalse(AchievementCollector.isCriterionTruthy(JsonParser.parseString("false")));
    assertFalse(AchievementCollector.isCriterionTruthy(JsonParser.parseString("0")));
    assertFalse(AchievementCollector.isCriterionTruthy(JsonParser.parseString("\"no\"")));
    assertFalse(AchievementCollector.isCriterionTruthy(null));
    assertFalse(AchievementCollector.isCriterionTruthy(JsonParser.parseString("{}")));
  }
}
