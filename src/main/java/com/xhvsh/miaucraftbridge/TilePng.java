package com.xhvsh.miaucraftbridge;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Tile PNG helpers. ImageIO (and its EncodeParam default) is not guaranteed
 * thread-safe, so encoding/decoding runs behind one lock - the cost is small
 * next to the disk reads and NBT parsing each region render already does.
 */
final class TilePng {

  private TilePng() {
  }

  static synchronized byte[] encode(BufferedImage image) throws IOException {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(1024, image.getWidth() * image.getHeight()));
    if (!ImageIO.write(image, "png", out)) {
      throw new IOException("no PNG writer available");
    }
    return out.toByteArray();
  }

  static synchronized BufferedImage decode(Path file, int expectedWidth, int expectedHeight) throws IOException {
    if (file == null || !java.nio.file.Files.isRegularFile(file)) {
      return newBuffered(Math.max(expectedWidth, expectedHeight));
    }
    BufferedImage img = ImageIO.read(file.toFile());
    if (img == null) {
      return newBuffered(Math.max(expectedWidth, expectedHeight));
    }
    return img;
  }

  /** Transparent 512x512 ARGB canvas (all map tiles are square). */
  static BufferedImage newBuffered(int size) {
    return new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
  }

  /**
   * Copies {@code tile} into {@code comp} at (offX, offZ). Each destination
   * pixel is the averaged square of {@code step}x{@code step} source pixels,
   * which keeps the higher zooms sharp and the low zooms soft where the
   * averaging necessarily blends away one-block detail.
   */
  static void drawScaled(BufferedImage tile, BufferedImage comp, int offX, int offZ, int step, int size) {
    int[] sub = new int[step * step];
    for (int dy = 0; dy < size; dy++) {
      for (int dx = 0; dx < size; dx++) {
        int sx = dx * step;
        int sy = dy * step;
        tile.getRGB(sx, sy, step, step, sub, 0, step);
        int color = average(sub, step);
        if (color != BlockColorTable.TRANSPARENT) {
          comp.setRGB(offX + dx, offZ + dy, color);
        }
      }
    }
  }

  private static int average(int[] rgba, int step) {
    long r = 0, g = 0, b = 0;
    int count = 0;
    for (int c : rgba) {
      if ((c >>> 24) == 0) continue; // fully transparent source pixel
      r += (c >> 16) & 0xFF;
      g += (c >> 8) & 0xFF;
      b += c & 0xFF;
      count++;
    }
    if (count == 0) return BlockColorTable.TRANSPARENT;
    int rr = (int) (r / count);
    int gg = (int) (g / count);
    int bb = (int) (b / count);
    return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
  }
}
