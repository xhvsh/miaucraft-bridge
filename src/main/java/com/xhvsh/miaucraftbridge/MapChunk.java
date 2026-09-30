package com.xhvsh.miaucraftbridge;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

/**
 * Per-column surface data for one chunk, colourised for the map. Built from a
 * live {@link ChunkSnapshot} (captured on the main thread) entirely off-thread,
 * which is squaremap's data-collection model: the world's real blocks and
 * biomes feed the colour pipeline instead of re-parsing region files.
 */
final class MapChunk {

  /** The few slices of a world a render pass needs; keeps MapChunk pure and testable. */
  interface ChunkSampler {
    int highest(int x, int z);

    Material material(int x, int y, int z);

    /** Biome key (e.g. "minecraft:plains" or "plains"); empty when unavailable. */
    String biome(int x, int y, int z);
  }

  /** Adapter over Bukkit's thread-safe snapshot for production use. */
  static final class SnapshotSampler implements ChunkSampler {
    private final ChunkSnapshot snapshot;

    SnapshotSampler(ChunkSnapshot snapshot) {
      this.snapshot = snapshot;
    }

    @Override
    public int highest(int x, int z) {
      return snapshot.getHighestBlockYAt(x, z);
    }

    @Override
    public Material material(int x, int y, int z) {
      return snapshot.getBlockType(x & 15, y, z & 15);
    }

    @Override
    public String biome(int x, int y, int z) {
      try {
        return snapshot.getBiome(x & 15, z & 15).getKey().getKey();
      } catch (Exception e) {
        return "";
      }
    }
  }

  /** How far below the heightmap a render must look before giving up. */
  private static final int SCAN_DEPTH = 16;

  final int chunkX;
  final int chunkZ;

  /** Per column (index = z * 16 + x): true when a visible pixel exists. */
  final boolean[] valid = new boolean[256];
  final int[] heights = new int[256];
  final int[] colors = new int[256];

  MapChunk(int chunkX, int chunkZ, ChunkSampler sampler) {
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
    for (int x = 0; x < 16; x++) {
      for (int z = 0; z < 16; z++) {
        int idx = z * 16 + x;
        int surface = sampler.highest(x, z);
        int y = surface;
        String biomeKey = null;
        while (y >= surface - SCAN_DEPTH) {
          Material material = sampler.material(x, y, z);
          if (material == null || material == Material.AIR) {
            y--;
            continue;
          }
          if (!BlockColorTable.skipThrough(key(material))) {
            if (biomeKey == null) {
              biomeKey = sampler.biome(x, y, z);
            }
            heights[idx] = y;
            colors[idx] = MapColors.colorOf(material, biomeKey);
            valid[idx] = true;
            break;
          }
          y--;
        }
        if (y < surface - SCAN_DEPTH) {
          valid[idx] = false;
        }
      }
    }
  }

  private static String key(Material m) {
    return m.getKey().getKey();
  }
}