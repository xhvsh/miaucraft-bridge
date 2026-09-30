package com.xhvsh.miaucraftbridge;

import org.bukkit.Material;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

/**
 * Resolves vanilla map colors for live block data, mirroring squaremap's
 * colouring model: the base colour comes from the game's own per-block map
 * colour (via {@code BlockState#getMapColor}), grass/leaves are re-tinted per
 * biome, water keeps its map colour, and anything the server cannot resolve
 * falls back to {@link BlockColorTable} so output is never worse than before.
 *
 * <p>Resolution is done once per material on the main thread during startup
 * (see {@link #init}) and cached; the per-column hot path only does a couple
 * of integer/key lookups plus a biome tint.
 */
final class MapColors {

  /** Blocks squaremap re-tints with the biome grass colour (full replace). */
  private static final String[] GRASS_TINT = {
      "grass_block", "short_grass", "tall_grass", "fern", "large_fern", "sugar_cane"
  };

  /** Biome-tinted leaves exactly as squaremap treats them (others are fixed colours). */
  private static final String[] FOLIAGE_TINT = {
      "oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "vine"
  };

  private static volatile boolean initialized;
  private static volatile boolean resolvedViaNms;
  private static int resolvedCount;

  /** Material base colour keyed on the Bukkit enum (thread-safe after {@link #init}). */
  private static final ConcurrentMap<Material, Integer> BASE_COLOR = new ConcurrentHashMap<>();

  /** Reflection handles, resolved once (main thread). */
  private static Method defaultBlockStateMethod;
  private static Method getMapColorMethod;

  private MapColors() {
  }

  /**
   * Wires reflection into the server's own block-colour machinery. Call once
   * on the main thread; failures are logged and merely shrink the cache above
   * the classic fallback table.
   */
  static synchronized void init(Logger log) {
    if (initialized) {
      return;
    }
    initialized = true;
    try {
      String sv = craftbukkitVersion();
      Class<?> magicNumbers = Class.forName("org.bukkit.craftbukkit." + sv + ".util.CraftMagicNumbers");
      Method getBlock = magicNumbers.getMethod("getBlock", Material.class);
      defaultBlockStateMethod = Class.forName("net.minecraft.world.level.block.Block").getMethod("defaultBlockState");
      Class<?> stateClass = Class.forName("net.minecraft.world.level.block.state.BlockState");
      Class<?> getterClass = Class.forName("net.minecraft.world.level.BlockGetter");
      Class<?> posClass = Class.forName("net.minecraft.core.BlockPos");
      getMapColorMethod = stateClass.getMethod("getMapColor", getterClass, posClass);
      for (Material m : Material.values()) {
        Integer c = resolveOnce(getBlock, m);
        if (c != null) {
          BASE_COLOR.put(m, c);
        }
      }
      resolvedCount = 0;
      for (Material m : Material.values()) {
        if (BASE_COLOR.containsKey(m)) {
          resolvedCount++;
        }
      }
      resolvedViaNms = resolvedCount > 100;
      log.info("map: vanilla map colors resolved via NMS for " + resolvedCount + "/" + Material.values().length
          + " materials (mode=" + (resolvedViaNms ? "nms" : "fallback-table") + ")");
    } catch (Exception e) {
      resolvedViaNms = false;
      defaultBlockStateMethod = null;
      getMapColorMethod = null;
      log.warning("map: NMS map-color resolution unavailable, using built-in table: " + e);
    }
  }

  /** True when the runtime server let us read its real palette. */
  static boolean usingVanillaColors() {
    return resolvedViaNms;
  }

  /** The ARGB surface colour for a live block+biome-key pair. */
  static int colorOf(Material material, String biomeKey) {
    String name = key(material);
    if (isGrassTint(name)) {
      double[] c = BiomeTint.climate(biomeKey);
      return BiomeTint.grass(c[0], c[1]);
    }
    if (isFoliageTint(name)) {
      double[] c = BiomeTint.climate(biomeKey);
      return BiomeTint.foliage(c[0], c[1]);
    }
    Integer base = BASE_COLOR.get(material);
    return base != null ? base : BlockColorTable.colorOf(name);
  }

  static int colorOf(Material material) {
    Integer base = BASE_COLOR.get(material);
    if (base != null) {
      return base;
    }
    return isGrassTint(key(material)) || isFoliageTint(key(material))
        ? 0xFF7CBD6B
        : BlockColorTable.colorOf(key(material));
  }

  // ------------------------------------------------------------ internals

  private static Integer resolveOnce(Method getBlock, Material m) {
    try {
      Object block = getBlock.invoke(null, m);
      if (block == null) {
        return null;
      }
      Object state = defaultBlockStateMethod.invoke(block);
      Object mapColor = getMapColorMethod.invoke(state, (Object) null, (Object) null);
      if (mapColor == null) {
        return null;
      }
      int rgb = rgbOf(mapColor);
      return rgb < 0 ? null : (0xFF000000 | rgb);
    } catch (Exception e) {
      return null;
    }
  }

  /** MapColor -> RGB via whichever field shape this server build uses. */
  private static int rgbOf(Object mapColor) throws Exception {
    Field col = null;
    try {
      col = mapColor.getClass().getField("col");
    } catch (NoSuchFieldException ignored) {
      try {
        col = mapColor.getClass().getDeclaredField("col");
      } catch (NoSuchFieldException ignored2) {
        return -1;
      }
    }
    try {
      col.setAccessible(true);
    } catch (RuntimeException ignored) {
    }
    if (col.getType() == int.class) {
      return col.getInt(mapColor);
    }
    if (col.getType() == int[].class) {
      int[] shades = (int[]) col.get(mapColor);
      // [LOWEST, LOW, NORMAL, HIGH] - NORMAL is the flat map colour.
      return shades.length > 2 ? shades[2] : (shades.length > 0 ? shades[0] : -1);
    }
    return -1;
  }

  private static String craftbukkitVersion() {
    String name = org.bukkit.Bukkit.getServer().getClass().getName();
    String prefix = "org.bukkit.craftbukkit.";
    if (name.startsWith(prefix)) {
      int dot = name.indexOf('.', prefix.length());
      if (dot > prefix.length()) {
        return name.substring(prefix.length(), dot);
      }
    }
    return name.substring(prefix.length());
  }

  private static String key(Material m) {
    return m.getKey().getKey();
  }

  private static boolean isGrassTint(String name) {
    for (String s : GRASS_TINT) {
      if (s.equals(name)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isFoliageTint(String name) {
    for (String s : FOLIAGE_TINT) {
      if (s.equals(name)) {
        return true;
      }
    }
    return false;
  }
}