package com.xhvsh.miaucraftbridge;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Renders one region (512x512 blocks) into a 512x512 ARGB tile, shaded by the
 * height difference versus the block one column to the north - the same edge
 * shading trick squaremap uses for its near-isometric look.
 */
final class MapRegionRenderer {

  static final int TILE = 512;

  private MapRegionRenderer() {
  }

  static BufferedImage render(Path regionDir, AnvilRegion region) throws IOException {
    int regX = region.regionX();
    int regZ = region.regionZ();
    BufferedImage img = TilePng.newBuffered(TILE);

    // Heights of the block row just north of this region, for edge shading of
    // the region's own row 0.
    int[] border = northBorderHeights(regionDir, regX, regZ);

    int[][] rows = new int[TILE][TILE]; // height of each rendered column for the next row's shading
    for (int czLocal = 0; czLocal < AnvilRegion.CHUNKS_PER_REGION; czLocal++) {
      for (int cxLocal = 0; cxLocal < AnvilRegion.CHUNKS_PER_REGION; cxLocal++) {
        Nbt.Compound tag = region.readChunk(cxLocal, czLocal);
        if (tag == null) {
          continue;
        }
        MapChunk chunk = new MapChunk(regX * 32 + cxLocal, regZ * 32 + czLocal, tag, true);
        for (int z = 0; z < 16; z++) {
          int row = czLocal * 16 + z;
          int[] prev = row == 0 ? border : rows[row - 1];
          int[] cur = rows[row];
          for (int x = 0; x < 16; x++) {
            int idx = z * 16 + x;
            if (!chunk.valid[idx]) {
              continue;
            }
            int gx = cxLocal * 16 + x;
            int h = chunk.heights[idx];
            img.setRGB(gx, row, shade(chunk.colors[idx], h - prev[gx], gx + row));
            cur[gx] = h;
          }
        }
      }
    }
    return img;
  }

  /**
   * Heights of the 512 columns at global z = regZ*512 - 1 (the chunk row just
   * north of the region). Returns a zeroed array when the neighbour region file
   * is missing so the border simply renders flat.
   */
  private static int[] northBorderHeights(Path regionDir, int regX, int regZ) {
    int[] out = new int[TILE];
    Path file = regionFile(regionDir, regX, regZ - 1);
    if (file == null) {
      return out;
    }
    AnvilRegion neighbour;
    try {
      neighbour = new AnvilRegion(file);
    } catch (IOException e) {
      return out;
    }
    try {
      for (int cxLocal = 0; cxLocal < AnvilRegion.CHUNKS_PER_REGION; cxLocal++) {
        Nbt.Compound tag = neighbour.readChunk(cxLocal, AnvilRegion.CHUNKS_PER_REGION - 1);
        if (tag == null) {
          continue;
        }
        // Heights-only parse: colors/biomes are irrelevant for edge shading.
        MapChunk chunk = new MapChunk(regX * 32 + cxLocal, regZ * 32 - 1, tag, false);
        for (int x = 0; x < 16; x++) {
          out[cxLocal * 16 + x] = chunk.heights[15 * 16 + x];
        }
      }
    } catch (IOException e) {
      java.util.Arrays.fill(out, 0);
    } finally {
      neighbour.close();
    }
    return out;
  }

  private static Path regionFile(Path regionDir, int regX, int regZ) {
    Path file = regionDir.resolve("r." + regX + "." + regZ + ".mca");
    return java.nio.file.Files.isRegularFile(file) ? file : null;
  }

  /**
   * Brightens/darkens a pixel by the slope against the column to the north,
   * with a tiny per-pixel jitter so flat areas don't look like a solid poster.
   */
  static int shade(int color, int diff, int pixel) {
    if ((color >>> 24) == 0) {
      return color;
    }
    double d = diff * 0.45 + (pixel % 2 == 0 ? -0.10 : 0.12);
    double m;
    if (d > 0.75) {
      m = 1.22;
    } else if (d < -0.75) {
      m = 0.80;
    } else {
      m = 1.0;
    }
    int r = (int) Math.min(255, Math.max(0, ((color >> 16) & 0xFF) * m));
    int g = (int) Math.min(255, Math.max(0, ((color >> 8) & 0xFF) * m));
    int b = (int) Math.min(255, Math.max(0, (color & 0xFF) * m));
    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }
}