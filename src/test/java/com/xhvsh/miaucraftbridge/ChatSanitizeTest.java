package com.xhvsh.miaucraftbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Web chat rows are untrusted and are broadcast to every player. */
class ChatSanitizeTest {

  @Test
  void aMegabyteRowIsClippedToTheConfiguredLength() {
    String huge = "x".repeat(5_000_000);
    String out = ChatBridge.sanitize(huge, 500);
    assertEquals(500, out.length());
  }

  @Test
  void escapeAndOtherControlCharactersAreRemoved() {
    String out = ChatBridge.sanitize("a\u001b[31mb\u0000c\u0007d\u007fe", 100);
    assertEquals("a[31mbcde", out);
    assertFalse(out.chars().anyMatch(Character::isISOControl));
  }

  @Test
  void whitespaceControlsBecomeSpacesSoLinesCannotBeForged() {
    assertEquals("one two three", ChatBridge.sanitize("one\ntwo\r\nthree".replace("\r\n", " "), 100));
    assertTrue(ChatBridge.sanitize("a\nb", 100).indexOf('\n') < 0);
  }

  @Test
  void usernameCapKeepsShortNamesIntact() {
    assertEquals("Steve_01", ChatBridge.sanitize("Steve_01", 32));
    assertEquals(32, ChatBridge.sanitize("n".repeat(200), 32).length());
  }
}
