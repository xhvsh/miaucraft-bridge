package com.xhvsh.miaucraftbridge;

/**
 * Biome temperature/rainfall approximation plus vanilla-style grass/foliage
 * tinting. The real MC values live inside the server jar (unavailable to this
 * plugin), so biomes are classified by key word and tinted with the classic
 * HSV gradient that map renderers use - the result is visually very close to
 * squaremap for the vanilla overworld without needing net.minecraft access.
 */
final class BiomeTint {

  private BiomeTint() {
  }

  /** (temperature, rainfall) derived from a biome key; both roughly vanilla range 0..2 / 0..1. */
  static double[] climate(String biomeKey) {
    String b = biomeKey == null ? "" : biomeKey.toLowerCase();
    if (b.contains("swamp") || b.contains("mangrove")) return new double[] {0.75, 0.90};
    if (b.contains("desert") || b.contains("badlands") || b.contains("mesa")) return new double[] {2.0, 0.0};
    if (b.contains("savanna")) return new double[] {1.9, 0.0};
    if (b.contains("jungle")) return new double[] {0.95, 0.90};
    if (b.contains("bamboo")) return new double[] {0.9, 0.8};
    if (b.contains("mushroom")) return new double[] {0.9, 1.0};
    if (b.contains("flower_forest")) return new double[] {0.7, 0.8};
    if (b.contains("forest") || b.contains("grove") || b.contains("cherry")) return new double[] {0.7, 0.8};
    if (b.contains("birch")) return new double[] {0.6, 0.6};
    if (b.contains("dark_forest")) return new double[] {0.7, 0.8};
    if (b.contains("old_growth") || b.contains("meadow")) return new double[] {0.5, 0.8};
    if (b.contains("dripstone") || b.contains("windswept")) return new double[] {0.5, 0.5};
    if (b.contains("plains") || b.contains("sunflower")) return new double[] {0.8, 0.4};
    if (b.contains("taiga")) return new double[] {0.25, 0.5};
    if (b.contains("snow") || b.contains("frozen") || b.contains("ice") || b.contains("glacier")) return new double[] {0.0, 0.4};
    if (b.contains("stony_peaks") || b.contains("jagged_peaks") || b.contains("frozen_peaks")) return new double[] {0.0, 0.5};
    if (b.contains("snowy")) return new double[] {0.0, 0.5};
    if (b.contains("cold")) return new double[] {0.2, 0.7};
    if (b.contains("ocean") || b.contains("river") || b.contains("beach") || b.contains("deep")) return new double[] {0.5, 0.5};
    if (b.contains("end") || b.contains("the_end")) return new double[] {0.5, 0.5};
    if (b.contains("nether") || b.contains("crimson") || b.contains("warped") || b.contains("soul") || b.contains("basalt"))
      return new double[] {2.0, 0.0};
    return new double[] {0.5, 0.5};
  }

  /** ARGB grass tint for a biome climate (hot/dry grass is pale + yellow, cold is dull). */
  static int grass(double temp, double rain) {
    double t = clamp(temp, 0, 1);
    double r = clamp(rain, 0, 1);
    // Swampy/dark biomes get a muddy green; desert-hot biomes go pale.
    double hue = 0.30 - 0.10 * t + 0.03 * r;
    double sat = clamp(0.42 + 0.34 * r - 0.06 * t, 0.12, 0.95);
    double val = clamp(0.58 + 0.12 * r - 0.08 * t, 0.25, 0.85);
    return hsvToRgb(hue, sat, val);
  }

  /** ARGB foliage tint (leaves). */
  static int foliage(double temp, double rain) {
    double t = clamp(temp, 0, 1);
    double r = clamp(rain, 0, 1);
    double hue = 0.27 - 0.08 * t + 0.03 * r;
    double sat = clamp(0.45 + 0.30 * r - 0.05 * t, 0.12, 0.95);
    double val = clamp(0.55 + 0.12 * r, 0.22, 0.80);
    return hsvToRgb(hue, sat, val);
  }

  /** Blends a biome tint over a base block color (tintWeight close to 1 = more tint). */
  static int blend(int base, int tint, double tintWeight) {
    int br = base >> 16 & 0xFF, bg = base >> 8 & 0xFF, bb = base & 0xFF;
    int tr = tint >> 16 & 0xFF, tg = tint >> 8 & 0xFF, tb = tint & 0xFF;
    double w = clamp(tintWeight, 0, 1);
    int r = (int) Math.round(br + (tr - br) * w);
    int g = (int) Math.round(bg + (tg - bg) * w);
    int b = (int) Math.round(bb + (tb - bb) * w);
    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }

  private static double clamp(double v, double min, double max) {
    return v < min ? min : Math.min(v, max);
  }

  /** Standard HSV->RGB returning an opaque int. hue in 0..1, sat/val in 0..1. */
  static int hsvToRgb(double h, double s, double v) {
    double chroma = v * s;
    double hp = (h - Math.floor(h)) * 6.0;
    double x = chroma * (1.0 - Math.abs(hp % 2.0 - 1.0));
    double r = 0, g = 0, b = 0;
    int sextant = (int) Math.floor(hp);
    switch (sextant) {
      case 0 -> { r = chroma; g = x; }
      case 1 -> { r = x; g = chroma; }
      case 2 -> { g = chroma; b = x; }
      case 3 -> { g = x; b = chroma; }
      case 4 -> { r = x; b = chroma; }
      default -> { r = chroma; b = x; }
    }
    double m = v - chroma;
    int rr = (int) Math.round((r + m) * 255);
    int gg = (int) Math.round((g + m) * 255);
    int bb = (int) Math.round((b + m) * 255);
    return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
  }
}