package com.xhvsh.miaucraftbridge;

import java.util.HashMap;
import java.util.Map;

/**
 * Decoded column data for one chunk: per column (x,z 0..15) the world surface
 * height and its pixel color. Parsed directly from the region-file NBT, so no
 * chunk is ever loaded into the server and the main thread is never touched.
 */
final class MapChunk {

  /** One 16x16-y section. */
  private static final class Section {
    final int minY;
    final String[] blockPalette;
    final long[] blockData;
    final int blockBits;
    final String[] biomePalette;
    final long[] biomeData;
    final int biomeBits;

    Section(int minY, String[] blockPalette, long[] blockData, String[] biomePalette, long[] biomeData) {
      this.minY = minY;
      this.blockPalette = blockPalette;
      this.blockData = blockData;
      this.blockBits = blockPalette.length <= 1 ? 0
          : Math.max(4, 32 - Integer.numberOfLeadingZeros(blockPalette.length - 1));
      this.biomePalette = biomePalette;
      this.biomeData = biomeData;
      this.biomeBits = biomePalette.length <= 1 ? 0
          : Math.max(4, 32 - Integer.numberOfLeadingZeros(biomePalette.length - 1));
    }
  }

  final int chunkX;
  final int chunkZ;

  /** Per column (index = z * 16 + x): true when a visible pixel exists. */
  final boolean[] valid = new boolean[256];
  final int[] heights = new int[256];
  final int[] colors = new int[256];

  private final Map<Integer, Section> sections = new HashMap<>();
  private int minWorldY = -64;
  private int maxWorldY = 319;

  MapChunk(int chunkX, int chunkZ, Nbt.Compound root, boolean resolveColors) {
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
    parseSections(root);
    int[] surface = surfaceHeights(root);
    for (int x = 0; x < 16; x++) {
      for (int z = 0; z < 16; z++) {
        int idx = z * 16 + x;
        int h = surface[idx];
        if (!resolveColors) {
          valid[idx] = true;
          heights[idx] = h;
          continue;
        }
        // scan down from the surface to the first visible block (plants/water
        // over foliage give the depth the renderer shades against)
        int y = h;
        String entry;
        while (y >= minWorldY) {
          entry = blockAt(x, y, z);
          String base = Nbt.baseId(entry);
          boolean snowyGrass = base.equals("grass_block") && Nbt.property(entry, "snowy", false);
          if (!BlockColorTable.skipThrough(base) || snowyGrass) {
            heights[idx] = y;
            colors[idx] = colorOf(entry, base, x, y, z, snowyGrass);
            valid[idx] = true;
            break;
          }
          y--;
        }
        if (y < minWorldY) {
          valid[idx] = false;
        }
      }
    }
  }

  private void parseSections(Nbt.Compound root) {
    Nbt.Tag.TagList sectionsList = root.list("sections");
    if (sectionsList == null || sectionsList.value == null) {
      return;
    }
    for (Nbt.Tag tag : sectionsList.value) {
      if (!(tag instanceof Nbt.Compound sec)) {
        continue;
      }
      int y = sec.integer("Y", 0);
      int minY = y * 16;
      minWorldY = Math.min(minWorldY, minY);
      maxWorldY = Math.max(maxWorldY, minY + 15);
      Nbt.Compound blockStates = sec.compound("block_states");
      String[] blockPalette = blockStates == null ? new String[0] : palette(blockStates);
      long[] blockData = blockStates == null ? null : blockStates.longArray("data");
      Nbt.Compound biomeHolder = sec.compound("biomes");
      String[] biomePalette = biomeHolder == null ? new String[0] : palette(biomeHolder);
      long[] biomeData = biomeHolder == null ? null : biomeHolder.longArray("data");
      sections.put(y, new Section(minY, blockPalette, blockData, biomePalette, biomeData));
    }
  }

  private static String[] palette(Nbt.Compound holder) {
    Nbt.Tag.TagList l = holder.list("palette");
    if (l == null || l.value == null) {
      return new String[0];
    }
    String[] out = new String[l.value.size()];
    for (int i = 0; i < l.value.size(); i++) {
      out[i] = l.value.get(i).stringValue("minecraft:air");
    }
    return out;
  }

  private int[] surfaceHeights(Nbt.Compound root) {
    long[] worldSurface = root.compound("Heightmaps") == null ? null
        : root.compound("Heightmaps").longArray("WORLD_SURFACE");
    int[] h = Nbt.unpackHeightmap(worldSurface);
    for (int idx = 0; idx < 256; idx++) {
      if (h[idx] <= minWorldY) {
        h[idx] = fallbackHeight(idx);
      }
    }
    return h;
  }

  private int fallbackHeight(int idx) {
    int x = idx & 15;
    int z = idx >> 4;
    for (Section sec : descendingSections()) {
      if (sec.blockPalette.length == 0) {
        continue;
      }
      int secMax = sec.minY + 15;
      for (int y = secMax; y >= sec.minY; y--) {
        String base = Nbt.baseId(blockAt(x, y, z, sec));
        if (!base.equals("air") && !base.equals("cave_air") && !base.equals("void_air")
            && !base.equals("water") && !base.equals("lava")) {
          return y;
        }
      }
    }
    return minWorldY;
  }

  private Section[] descendingSections() {
    return sections.values().stream()
        .sorted((a, b) -> Integer.compare(b.minY, a.minY))
        .toArray(Section[]::new);
  }

  private Section sectionForY(int y) {
    return sections.get(Math.floorDiv(y, 16));
  }

  /** Resolves the full state string of one block (three-block palette lookup). */
  private String blockAt(int x, int y, int z) {
    Section sec = sectionForY(y);
    return blockAt(x, y, z, sec);
  }

  private String blockAt(int x, int y, int z, Section sec) {
    if (sec == null || sec.blockPalette.length == 0) {
      return "minecraft:air";
    }
    int localY = Math.floorMod(y, 16);
    int index = (localY << 8) | (z << 4) | x;
    int paletteIndex = sec.blockBits == 0 ? 0
        : Nbt.unpackPacked(sec.blockData, sec.blockBits, index, false);
    if (paletteIndex < 0 || paletteIndex >= sec.blockPalette.length) {
      return "minecraft:air";
    }
    return sec.blockPalette[paletteIndex];
  }

  private String biomeAt(int x, int y, int z) {
    Section sec = sectionForY(y);
    if (sec == null || sec.biomePalette.length == 0) {
      return "minecraft:plains";
    }
    int localY = Math.floorMod(y, 16);
    int qx = x >> 2;
    int qy = localY >> 2;
    int qz = z >> 2;
    int index = (qy << 4) | (qz << 2) | qx;
    int paletteIndex = sec.biomeBits == 0 ? 0
        : Nbt.unpackPacked(sec.biomeData, sec.biomeBits, index, false);
    if (paletteIndex < 0 || paletteIndex >= sec.biomePalette.length) {
      return "minecraft:plains";
    }
    return sec.biomePalette[paletteIndex];
  }

  private int colorOf(String entry, String base, int x, int y, int z, boolean snowyGrass) {
    if (snowyGrass) {
      return 0xFFF2F2F2;
    }
    if (BlockColorTable.isTransparent(base)) {
      return BlockColorTable.TRANSPARENT;
    }
    int c = BlockColorTable.colorOf(base);
    if (BlockColorTable.isTintable(base)) {
      double[] climate = BiomeTint.climate(biomeAt(x, y, z));
      int tint = base.endsWith("_leaves") ? BiomeTint.foliage(climate[0], climate[1])
          : BiomeTint.grass(climate[0], climate[1]);
      double weight = base.equals("grass_block") ? 0.5 : 0.55;
      c = BiomeTint.blend(c, tint, weight);
    }
    return c;
  }
}


