package com.xhvsh.miaucraftbridge;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pipeline checks for the live map data path (no server required): the
 * per-column scan over a ChunkSampler, color resolution incl. biome tinting,
 * water visibility, and the flat region renderer. Biome keys are plain strings
 * on purpose - the Bukkit Biome enum needs a live registry.
 */
class MapPipelineTest {

  @TempDir
  Path temp;

  // ------------------------------------------------------------- column scan

  /** A sampler that renders blocks purely from per-test constants. */
  private static final class FakeSampler implements MapChunk.ChunkSampler {
    final int surface;
    final int waterY;   // -1 when no water in this world
    final int plantAbove; // extra non-colliding blocks on top (grass, tulips...)
    final Material ground;
    final String biomeKey;

    FakeSampler(Material ground, int surface, int waterY, int plantAbove, String biomeKey) {
      this.ground = ground;
      this.surface = surface;
      this.waterY = waterY;
      this.plantAbove = plantAbove;
      this.biomeKey = biomeKey;
    }

    @Override
    public int highest(int x, int z) {
      return surface + plantAbove;
    }

    @Override
    public Material material(int x, int y, int z) {
      if (waterY >= 0 && y == waterY && y <= surface) {
        return Material.WATER;
      }
      if (y > surface) {
        return y <= surface + plantAbove ? Material.SHORT_GRASS : Material.AIR;
      }
      if (y == surface) {
        return ground;
      }
      return Material.STONE;
    }

    @Override
    public String biome(int x, int y, int z) {
      return biomeKey;
    }
  }

  private static MapChunk chunk(Material ground, int surface, int waterY, int plantAbove, String biomeKey) {
    return new MapChunk(0, 0, new FakeSampler(ground, surface, waterY, plantAbove, biomeKey));
  }

  @Test
  void scanSkipsPlantsAndRendersGroundUnderneath() {
    MapChunk c = chunk(Material.GRASS_BLOCK, 70, -1, 2, "minecraft:plains");
    assertTrue(c.valid[0]);
    assertEquals(70, c.heights[0], "height must be the ground block, not the plants");
    double[] cl = BiomeTint.climate("plains");
    assertEquals(BiomeTint.grass(cl[0], cl[1]), c.colors[0], "grass is fully biome-tinted");
  }

  @Test
  void scanTreatsTransparentPlantOnDirtAsDirt() {
    MapChunk c = chunk(Material.DIRT, 64, -1, 1, "minecraft:plains");
    assertTrue(c.valid[0]);
    assertEquals(BlockColorTable.colorOf("dirt"), c.colors[0]);
  }

  @Test
  void waterIsAVisibleSurfaceNow() {
    MapChunk c = chunk(Material.GRASS_BLOCK, 62, 62, 0, "minecraft:ocean");
    assertTrue(c.valid[0], "water columns must render");
    assertEquals(62, c.heights[0]);
    assertEquals(BlockColorTable.colorOf("water"), c.colors[0],
        "water keeps its flat vanilla map colour (no per-biome water tint)");
  }

  @Test
  void allAirColumnIsInvalid() {
    MapChunk c = new MapChunk(0, 0, new MapChunk.ChunkSampler() {
      @Override
      public int highest(int x, int z) {
        return 64;
      }

      @Override
      public Material material(int x, int y, int z) {
        return Material.AIR;
      }

      @Override
      public String biome(int x, int y, int z) {
        return "minecraft:plains";
      }
    });
    assertFalse(c.valid[0]);
  }

  // ---------------------------------------------------------------- colors

  @Test
  void grassBlockUsesFullBiomeTint() {
    for (String b : new String[] {"minecraft:savanna", "minecraft:forest", "minecraft:snowy_taiga"}) {
      double[] cl = BiomeTint.climate(b);
      assertEquals(BiomeTint.grass(cl[0], cl[1]), MapColors.colorOf(Material.GRASS_BLOCK, b), b);
    }
  }

  @Test
  void leavesTintSetMatchesSquaremapExactList() {
    double[] cl = BiomeTint.climate("forest");
    assertEquals(BiomeTint.foliage(cl[0], cl[1]), MapColors.colorOf(Material.OAK_LEAVES, "minecraft:forest"));
    // spruce leaves are NOT in squaremap's foliage set (fixed colour)
    assertEquals(BlockColorTable.colorOf("spruce_leaves"),
        MapColors.colorOf(Material.SPRUCE_LEAVES, "minecraft:forest"));
  }

  @Test
  void colorsFallBackToBuiltinTableWithoutNms() {
    assertEquals(BlockColorTable.colorOf("sand"), MapColors.colorOf(Material.SAND, "minecraft:desert"));
    assertEquals(BlockColorTable.colorOf("water"), MapColors.colorOf(Material.WATER, "minecraft:ocean"));
  }

  // ------------------------------------------------------------ region render

  @Test
  void regionRendererBlitsFlatWithoutShading() {
    MapChunk a = chunk(Material.GRASS_BLOCK, 70, -1, 0, "minecraft:plains");
    int grass = a.colors[0]; // same color every column of chunk A
    List<MapChunk> chunks = new ArrayList<>();
    chunks.add(a);

    BufferedImage img = MapRegionRenderer.render(chunks, 0, 0);
    assertEquals(512, img.getWidth());
    assertEquals(512, img.getHeight());

    // flat blit: no per-pixel jitter, so equal ground means equal pixels
    assertEquals(grass, img.getRGB(4, 4));
    assertEquals(grass, img.getRGB(4, 5));
    assertEquals(grass, img.getRGB(15, 15));
    assertNotEquals(grass, img.getRGB(20, 20), "chunk only occupies its own 16x16 area");
  }

  @Test
  void regionRendererIgnoresChunksOutsideRegion() {
    MapChunk other = new MapChunk(40, 40, new FakeSampler(Material.DIRT, 1, -1, 0, "minecraft:plains"));
    List<MapChunk> chunks = new ArrayList<>();
    chunks.add(other);
    BufferedImage img = MapRegionRenderer.render(chunks, 0, 0);
    assertTrue(MapRegionRenderer.isBlank(img), "foreign chunk must be dropped");
  }

  // ------------------------------------------------------------- drawScaled

  @Test
  void drawScaledAveragesTileAndSkipsTransparency() {
    BufferedImage tile = TilePng.newBuffered(8);
    tile.setRGB(0, 0, 0xFFFF0000);
    tile.setRGB(1, 0, 0xFFFF0000);
    tile.setRGB(0, 1, 0xFFFF0000);
    tile.setRGB(1, 1, 0xFFFF0000);
    BufferedImage comp = TilePng.newBuffered(4);
    TilePng.drawScaled(tile, comp, 1, 2, 2, 2);
    assertEquals(0xFFFF0000, comp.getRGB(1, 2), "solid averaged block");
    assertEquals(0, (comp.getRGB(3, 3) >>> 24), "blank dst stays transparent");
  }

  // ------------------------------------------------------------- storage key

  @Test
  void tileKeyUsesBasePathWorldZoomCoords() {
    assertEquals("world/3/1_-2.png", SupabaseStorage.key("", "world", 3, 1, -2));
    assertEquals("live/world/0/4_0.png", SupabaseStorage.key("live", "world", 0, 4, 0));
  }

  @Test
  void unknownBlockFallsBackStable() {
    int a = BlockColorTable.colorOf("totally_not_a_block");
    int b = BlockColorTable.colorOf("totally_not_a_block");
    assertEquals(a, b);
    assertNotEquals(0, (a & 0xFF000000));
  }
}