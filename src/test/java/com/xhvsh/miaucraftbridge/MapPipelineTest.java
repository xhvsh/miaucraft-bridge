package com.xhvsh.miaucraftbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks for the live-map disk path: region-file coordinates,
 * NBT decode, column color resolution and the 512px renderer, all exercised
 * against a synthetic region file built from scratch (no server required).
 */
class MapPipelineTest {

  @TempDir
  Path temp;

  // ------------------------------------------------------------- coords

  @Test
  void regionCoordsParseHandlesNegatives() {
    assertCoords("r.1.-2.mca", 1, -2);
    assertCoords("r.-5.7.mca", -5, 7);
    assertCoords("r.0.0.mca", 0, 0);
    assertCoords("r.-4.-6.mca", -4, -6);
    assertCoords("r.123.456.mca", 123, 456);
  }

  private static void assertCoords(String name, int x, int z) {
    int[] rc = AnvilRegion.parseCoords(name);
    assertEquals(x, rc[0], name);
    assertEquals(z, rc[1], name);
  }

  @Test
  void malformedRegionNameIsRejected() {
    int[] rc = AnvilRegion.parseCoords("r.1.-2.txt");
    assertEquals(Integer.MIN_VALUE, rc[0]);
  }

  // ------------------------------------------------------------- render

  /**
   * Builds r.0.0.mca holding one chunk whose palette is grass_block over water
   * (columns x<8 are water-topped and must render transparent, x>=8 opaque
   * grass blended with the plains biome tint), then renders the region tile.
   */
  @Test
  void syntheticRegionRendersExpectedSurface() throws IOException {
    Path regionFile = writeSyntheticRegion();
    AnvilRegion region = new AnvilRegion(regionFile);
    assertEquals(0, region.regionX());
    assertEquals(0, region.regionZ());

    Nbt.Compound tag = region.readChunk(0, 0);
    MapChunk chunk = new MapChunk(0, 0, tag, true);
    // grass column (x=10): visible with a height of 64
    assertTrue(chunk.valid[0 * 16 + 10]);
    assertEquals(64, chunk.heights[0 * 16 + 10]);
    // water column (x=3): the scan gives up, nothing to draw
    assertTrue(!chunk.valid[0 * 16 + 3]);

    BufferedImage img = MapRegionRenderer.render(temp, region);
    assertEquals(512, img.getWidth());
    assertEquals(512, img.getHeight());

    int expected = expectedGrassColor();
    // row 5 is above the region's row-0 border edge, so shading is neutral
    int pixel = img.getRGB(10, 5);
    assertEquals(expected, pixel, "grass column should render the biome-tinted blend");
    // water columns have no visible pixel
    int waterPixel = img.getRGB(3, 5);
    assertEquals(0, (waterPixel >>> 24), "water column must stay transparent");
    // the rest of the region (no chunks) is empty
    assertEquals(0, (img.getRGB(400, 400) >>> 24), "unwritten area must stay transparent");

    region.close();
  }

  private int expectedGrassColor() {
    double[] climate = BiomeTint.climate("minecraft:plains");
    int tint = BiomeTint.grass(climate[0], climate[1]);
    return BiomeTint.blend(BlockColorTable.colorOf("grass_block"), tint, 0.5);
  }

  // ------------------------------------------------------------- helpers

  /** A chunk top at y=64 (section Y=4) with a flat WORLD_SURFACE of 64. */
  private Path writeSyntheticRegion() throws IOException {
    Path dir = Files.createDirectories(temp.resolve("s"));
    Path file = dir.resolve("r.0.0.mca");
    byte[] nbt = chunkNbt();
    byte[] zlib = deflate(nbt);
    ByteArrayOutputStream capsule = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(capsule);
    out.writeInt(zlib.length + 5); // 4-byte length + compression byte + payload
    out.writeByte(2);              // zlib
    out.write(zlib);
    byte[] payload = capsule.toByteArray();

    int sectors = (payload.length + 4095) / 4096;
    byte[] region = new byte[8192 + sectors * 4096];
    region[0] = 0; // sector offset 2 (header occupies sectors 0-1)
    region[1] = 0;
    region[2] = 2;
    region[3] = (byte) sectors;
    System.arraycopy(payload, 0, region, 2 * 4096, payload.length);
    Files.write(file, region);
    return file;
  }

  private byte[] chunkNbt() throws IOException {
    int[] hm = new int[256];
    java.util.Arrays.fill(hm, 64); // flat WORLD_SURFACE at y=64 every column
    long[] heightmap = pack9(hm);
    int[] states = new int[256];
    for (int i = 0; i < 256; i++) {
      int x = i & 15;
      states[i] = x < 8 ? 1 : 0; // water under the west half
    }
    long[] data = pack(states, 4);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(baos);
    out.writeByte(10); // root compound
    out.writeUTF("");
    // Heightmaps
    out.writeByte(10);
    out.writeUTF("Heightmaps");
    out.writeByte(12);
    out.writeUTF("WORLD_SURFACE");
    out.writeInt(heightmap.length);
    for (long v : heightmap) out.writeLong(v);
    out.writeByte(0);
    // sections list
    out.writeByte(9);
    out.writeUTF("sections");
    out.writeByte(10);
    out.writeInt(1);
    // section compound (unnamed list element)
    out.writeByte(3);
    out.writeUTF("Y");
    out.writeInt(4);
    // block_states
    out.writeByte(10);
    out.writeUTF("block_states");
    out.writeByte(9);
    out.writeUTF("palette");
    out.writeByte(8);
    out.writeInt(2);
    out.writeUTF("minecraft:grass_block");
    out.writeUTF("minecraft:water");
    out.writeByte(12);
    out.writeUTF("data");
    out.writeInt(data.length);
    for (long v : data) out.writeLong(v);
    out.writeByte(0);
    // biomes
    out.writeByte(10);
    out.writeUTF("biomes");
    out.writeByte(9);
    out.writeUTF("palette");
    out.writeByte(8);
    out.writeInt(1);
    out.writeUTF("minecraft:plains");
    out.writeByte(0);
    out.writeByte(0); // end section
    out.writeByte(0); // end root
    return baos.toByteArray();
  }

  private static long[] pack(int[] values, int bits) {
    int n = values.length;
    long[] out = new long[(n * bits + 63) / 64];
    long mask = (1L << bits) - 1;
    for (int i = 0; i < n; i++) {
      long start = (long) i * bits;
      int li = (int) (start >>> 6);
      int sh = (int) (start & 63);
      long v = values[i] & mask;
      out[li] |= v << sh;
      if (sh + bits > 64) {
        out[li + 1] |= v >>> (64 - sh);
      }
    }
    return out;
  }

  /** Packs 256 nine-bit values into the heightmap stream (LSB-first, like unpack). */
  private static long[] pack9(int[] values) {
    long[] out = new long[(values.length * 9 + 63) / 64];
    for (int i = 0; i < values.length; i++) {
      long start = (long) i * 9;
      int li = (int) (start >>> 6);
      int sh = (int) (start & 63);
      long v = values[i] & 0x1FFL;
      out[li] |= v << sh;
      if (sh + 9 > 64) {
        out[li + 1] |= v >>> (64 - sh);
      }
    }
    return out;
  }

  private static byte[] deflate(byte[] in) {
    Deflater deflater = new Deflater();
    deflater.setInput(in);
    deflater.finish();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[512];
    while (!deflater.finished()) {
      int n = deflater.deflate(buf);
      out.write(buf, 0, n);
    }
    deflater.end();
    return out.toByteArray();
  }

  // ------------------------------------------------------------- bits

  @Test
  void packAndUnpackAgree() {
    int[] values = new int[256];
    for (int i = 0; i < 256; i++) values[i] = (i * 7) % 5; // 3-bit pattern
    long[] packed = pack(values, 3);
    int[] back = new int[256];
    for (int i = 0; i < 256; i++) back[i] = Nbt.unpackPacked(packed, 3, i, false);
    for (int i = 0; i < 256; i++) assertEquals(values[i], back[i], "index " + i);

    // 9-bit heightmap round-trip
    int[] heights = new int[256];
    for (int i = 0; i < 256; i++) heights[i] = 100 + (i * 13) % 50;
    long[] hpacked = pack9(heights);
    int[] hback = Nbt.unpackHeightmap(hpacked);
    for (int i = 0; i < 256; i++) assertEquals(heights[i], hback[i], "height index " + i);
  }

  // ------------------------------------------------------------- drawScaled

  @Test
  void drawScaledAveragesTileAndSkipsTransparency() throws IOException {
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
    assertEquals("world/3/1_-2.png",
        SupabaseStorage.key("", "world", 3, 1, -2));
    assertEquals("live/world/0/4_0.png",
        SupabaseStorage.key("live", "world", 0, 4, 0));
  }

  @Test
  void unknownBlockFallsBackStable() {
    int a = BlockColorTable.colorOf("totally_not_a_block");
    int b = BlockColorTable.colorOf("totally_not_a_block");
    assertEquals(a, b);
    assertNotEquals(0, (a & 0xFF000000));
  }
}