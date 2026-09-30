package com.xhvsh.miaucraftbridge;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * An Anvil {@code .mca} region file: 32x32 chunks of 4096 byte-sectors. The
 * first 8 KiB hold a 1024-entry directory (3-byte sector offset + 1-byte count
 * per chunk, then a parallel timestamp block), each chunk payload is a 4-byte
 * big-endian length followed by a compression byte and the chunk's NBT.
 *
 * <p>Keeps one lazily-opened random-access handle per instance so a render
 * pass that reads every chunk does not reopen the file a thousand times;
 * {@link #readChunk} is synchronized so two render workers sharing the file
 * (a region plus the neighbour border row) never seek over each other.
 */
final class AnvilRegion {

  static final int CHUNKS_PER_REGION = 32;

  /** Kept small so a very large pending sweep cannot pin memory. */
  private static final int MAX_CHUNK_PAYLOAD = 4 * 1024 * 1024;

  private final Path file;
  private final int regionX;
  private final int regionZ;
  private final int[] offsets = new int[1024];
  private final int[] counts = new int[1024];
  private RandomAccessFile handle;
  private long sizeHint = -1;

  AnvilRegion(Path file) throws IOException {
    if (file == null || !Files.isRegularFile(file)) {
      throw new IOException("Region file does not exist: " + file);
    }
    this.file = file;
    String name = file.getFileName().toString(); // e.g. r.1.-2.mca
    int[] coords = parseCoords(name);
    this.regionX = coords[0];
    this.regionZ = coords[1];
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      this.sizeHint = Math.max(0, raf.length());
      byte[] header = new byte[8192];
      raf.readFully(header);
      for (int i = 0; i < 1024; i++) {
        int b = i << 2;
        offsets[i] = ((header[b] & 0xFF) << 16) | ((header[b + 1] & 0xFF) << 8) | (header[b + 2] & 0xFF);
        counts[i] = header[b + 3] & 0xFF;
      }
    }
  }

  int regionX() {
    return regionX;
  }

  int regionZ() {
    return regionZ;
  }

  Path file() {
    return file;
  }

  long lastModified() {
    try {
      return Files.getLastModifiedTime(file).toMillis();
    } catch (IOException e) {
      return -1;
    }
  }

  /** Local chunk coords are 0..31. Returns null when the chunk does not exist. */
  synchronized Nbt.Compound readChunk(int localChunkX, int localChunkZ) throws IOException {
    int index = (localChunkX & 31) + (localChunkZ & 31) * CHUNKS_PER_REGION;
    int offset = offsets[index];
    int count = counts[index];
    if (offset == 0 || count == 0) {
      return null;
    }
    RandomAccessFile raf = handle();
    long position = (long) offset * 4096L;
    if (position + 5 > sizeHint) {
      return null;
    }
    raf.seek(position);
    int payloadLength = raf.readInt();
    if (payloadLength <= 1 || payloadLength > MAX_CHUNK_PAYLOAD) {
      return null;
    }
    int compression = raf.readUnsignedByte();
    byte[] payload = new byte[payloadLength - 1];
    raf.readFully(payload);
    byte[] inflated;
    switch (compression) {
      case 1 -> inflated = inflate(payload, new GZIPInputStream(new java.io.ByteArrayInputStream(payload)));
      case 2 -> inflated = inflate(payload, new InflaterInputStream(new java.io.ByteArrayInputStream(payload)));
      case 3 -> inflated = payload;
      default -> {
        return null; // unknown/uncompressed variants aren't written by modern servers
      }
    }
    return Nbt.read(inflated);
  }

  private RandomAccessFile handle() throws IOException {
    if (handle == null) {
      handle = new RandomAccessFile(file.toFile(), "r");
    }
    return handle;
  }

  void close() {
    try {
      if (handle != null) {
        handle.close();
        handle = null;
      }
    } catch (IOException ignored) {
    }
  }

  private static byte[] inflate(byte[] original, InputStream decompressor) throws IOException {
    try (decompressor) {
      byte[] buf = new byte[16384];
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(original.length * 2);
      int n;
      while ((n = decompressor.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }

  /** r.<x>.<z>.mca -> [regionX, regionZ], or [MIN, MIN] when the name is malformed. */
  static int[] parseCoords(String name) {
    try {
      if (name == null || name.length() < 8 || !name.endsWith(".mca")) {
        return new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE};
      }
      String core = name.substring(2, name.length() - 4); // strip "r." prefix and ".mca"
      String[] parts = core.split("\\.");
      if (parts.length != 2) {
        return new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE};
      }
      return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    } catch (Exception e) {
      return new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE};
    }
  }

  @Override
  public String toString() {
    return "(region " + regionX + "," + regionZ + " " + file.getFileName() + ")";
  }
}