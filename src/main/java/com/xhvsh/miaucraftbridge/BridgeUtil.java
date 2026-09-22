package com.xhvsh.miaucraftbridge;

import org.bukkit.World;

import java.time.Instant;

final class BridgeUtil {

  private BridgeUtil() {}

  static String nowIso() {
    return Instant.now().toString();
  }

  static String dimensionOf(World world) {
    if (world == null) return null;
    return switch (world.getEnvironment()) {
      case NORMAL -> "overworld";
      case NETHER -> "nether";
      case THE_END -> "end";
      default -> null;
    };
  }

  static double round(double v, int decimals) {
    double scale = Math.pow(10, decimals);
    return Math.round(v * scale) / scale;
  }
}