package com.xhvsh.miaucraftbridge;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Renders one region (512x512 blocks) into a 512x512 ARGB tile, entirely from
 * the tracker's in-memory chunk data. No height shading and no per-pixel
 * jitter: each visible column is a flat vanilla map colour, so terrain comes
 * out as clean flat-colour patches instead of noisy speckle. Chunks the
 * tracker has not captured yet are left transparent.
 */
final class MapRegionRenderer {

  static final int TILE = 512;

  private MapRegionRenderer() {
  }

  /**
   * Draws the given (already captured) chunks into a fresh region tile.
   * Chunks outside this region are ignored.
   */
  static BufferedImage render(List<MapChunk> chunks, int regX, int regZ) {
    BufferedImage img = TilePng.newBuffered(TILE);
    for (MapChunk chunk : chunks) {
      if (chunk.chunkX < regX * 32 || chunk.chunkX >= regX * 32 + 32
          || chunk.chunkZ < regZ * 32 || chunk.chunkZ >= regZ * 32 + 32) {
        continue;
      }
      int x0 = (chunk.chunkX - regX * 32) * 16;
      int z0 = (chunk.chunkZ - regZ * 32) * 16;
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          int idx = z * 16 + x;
          if (chunk.valid[idx]) {
            img.setRGB(x0 + x, z0 + z, chunk.colors[idx]);
          }
        }
      }
    }
    return img;
  }

  /** True when no pixel was actually drawn (nothing to publish). */
  static boolean isBlank(BufferedImage img) {
    int[] px = img.getRGB(0, 0, TILE, TILE, null, 0, TILE);
    for (int v : px) {
      if ((v >>> 24) != 0) {
        return false;
      }
    }
    return true;
  }
}