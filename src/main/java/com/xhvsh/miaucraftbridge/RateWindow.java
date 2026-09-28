package com.xhvsh.miaucraftbridge;

import java.util.HashMap;
import java.util.Map;

/**
 * Collapses repeated identical messages inside a time window.
 *
 * This exists so a broken database cannot fill the console mirror with its own
 * failure: every flush logs the same transport error, and capturing each one
 * would turn a single outage into thousands of rows. The first occurrence of a
 * message in a window is kept, because that is the one worth reading, and the
 * rest are only counted.
 */
final class RateWindow {

  private final long windowMs;
  private final int maxKeys;
  private final int maxPerWindow;

  private final Map<String, Long> seen = new HashMap<>();
  private long windowStart;
  private int accepted;
  private long suppressed;

  RateWindow(long windowMs, int maxKeys, int maxPerWindow) {
    this.windowMs = Math.max(1L, windowMs);
    this.maxKeys = Math.max(1, maxKeys);
    this.maxPerWindow = Math.max(1, maxPerWindow);
  }

  /**
   * @return true when this message should be stored. Repeated calls with the
   *     same key inside the window return false.
   */
  synchronized boolean accept(String key) {
    roll();
    if (accepted >= maxPerWindow) {
      suppressed++;
      return false;
    }
    if (seen.containsKey(key)) {
      suppressed++;
      return false;
    }
    if (seen.size() >= maxKeys) seen.clear();
    seen.put(key, windowStart);
    accepted++;
    return true;
  }

  private void roll() {
    long now = System.currentTimeMillis();
    if (windowStart == 0L) {
      windowStart = now;
      return;
    }
    if (now - windowStart < windowMs) return;
    windowStart = now;
    accepted = 0;
    seen.clear();
  }

  synchronized long suppressed() {
    return suppressed;
  }

  synchronized int acceptedInWindow() {
    return accepted;
  }
}
