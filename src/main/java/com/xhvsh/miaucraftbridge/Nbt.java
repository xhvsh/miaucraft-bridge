package com.xhvsh.miaucraftbridge;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal NBT reader for Minecraft region files (the "anvil" on-disk format,
 * stable since 1.13 / world height changes included).
 *
 * <p>Tags are decoded into the nested classes below; only unsigned big-endian
 * scalars and the two long-array packed containers the live map actually needs
 * get first-class support, while every other tag is still fully parsed so an
 * unknown chunk shape can never desync the stream.
 */
public final class Nbt {

  public static final int TAG_END = 0;
  public static final int TAG_BYTE = 1;
  public static final int TAG_SHORT = 2;
  public static final int TAG_INT = 3;
  public static final int TAG_LONG = 4;
  public static final int TAG_FLOAT = 5;
  public static final int TAG_DOUBLE = 6;
  public static final int TAG_BYTE_ARRAY = 7;
  public static final int TAG_STRING = 8;
  public static final int TAG_LIST = 9;
  public static final int TAG_COMPOUND = 10;
  public static final int TAG_INT_ARRAY = 11;
  public static final int TAG_LONG_ARRAY = 12;

  private Nbt() {
  }

  /** Parses a full root compound (the stream must start at the root tag). */
  public static Compound read(InputStream in) throws IOException {
    DataInputStream data = new DataInputStream(in);
    Compound root = (Compound) readTag(data);
    if (root == null) {
      throw new IOException("NBT root is not a compound");
    }
    return root;
  }

  /** Parses a full root compound from raw bytes without decompressing. */
  public static Compound read(byte[] payload) throws IOException {
    return read(new ByteArrayInputStream(payload));
  }

private static Tag readTag(DataInputStream in) throws IOException {
    int id = in.readUnsignedByte();
    if (id == TAG_END) {
      return null;
    }
    String name = in.readUTF(); // tag name (unused for traversal)
    Tag payload = readPayload(in, id);
    payload.bindName(name);
    return payload;
  }

  private static Tag readPayload(DataInputStream in, int id) throws IOException {
    return switch (id) {
      case TAG_BYTE -> new Tag.Byte(in.readByte());
      case TAG_SHORT -> new Tag.Short(in.readShort());
      case TAG_INT -> new Tag.Int(in.readInt());
      case TAG_LONG -> new Tag.Long(in.readLong());
      case TAG_FLOAT -> new Tag.Float(in.readFloat());
      case TAG_DOUBLE -> new Tag.Double(in.readDouble());
      case TAG_STRING -> new Tag.Str(in.readUTF());
      case TAG_BYTE_ARRAY -> {
        int len = in.readInt();
        byte[] arr = new byte[len];
        in.readFully(arr);
        yield new Tag.ByteArray(arr);
      }
      case TAG_INT_ARRAY -> {
        int len = in.readInt();
        int[] arr = new int[len];
        for (int i = 0; i < len; i++) {
          arr[i] = in.readInt();
        }
        yield new Tag.IntArray(arr);
      }
      case TAG_LONG_ARRAY -> {
        int len = in.readInt();
        long[] arr = new long[len];
        for (int i = 0; i < len; i++) {
          arr[i] = in.readLong();
        }
        yield new Tag.LongArray(arr);
      }
      case TAG_LIST -> {
        int elementId = in.readUnsignedByte();
        int len = in.readInt();
        List<Tag> items = new ArrayList<>(Math.min(len, 4096));
        for (int i = 0; i < len; i++) {
          Tag item = readPayload(in, elementId);
          if (item != null) {
            items.add(item);
          }
        }
        yield new Tag.TagList(elementId, items);
      }
      case TAG_COMPOUND -> {
        Compound c = new Compound();
        Tag child;
        while ((child = readTag(in)) != null) {
          c.put(child);
        }
        yield c;
      }
      default -> throw new IOException("Unknown NBT tag id " + id);
    };
  }

/** Base class for the decoded tags below. */
  public abstract static class Tag {
    String name;

    Tag() {
      this(null);
    }

    Tag(String name) {
      this.name = name;
    }

    /** Attaches the name read from the stream (parse-time only, so named lookup works). */
    void bindName(String name) {
      this.name = name;
    }

    public String stringValue(String fallback) {
      return fallback;
    }

    public int intValue(int fallback) {
      return fallback;
    }

    public long longValue(long fallback) {
      return fallback;
    }

    public boolean boolValue(boolean fallback) {
      return fallback;
    }

    public static final class Byte extends Tag {
      public final byte value;

      Byte(byte value) {
        this.value = value;
      }

      @Override
      public int intValue(int fallback) {
        return value;
      }

      @Override
      public boolean boolValue(boolean fallback) {
        return value != 0;
      }
    }

    public static final class Short extends Tag {
      public final short value;

      Short(short value) {
        this.value = value;
      }

      @Override
      public int intValue(int fallback) {
        return value;
      }
    }

    public static final class Int extends Tag {
      public final int value;

      Int(int value) {
        this.value = value;
      }

      @Override
      public int intValue(int fallback) {
        return value;
      }

      @Override
      public long longValue(long fallback) {
        return value;
      }
    }

    public static final class Long extends Tag {
      public final long value;

      Long(long value) {
        this.value = value;
      }

      @Override
      public long longValue(long fallback) {
        return value;
      }
    }

    public static final class Float extends Tag {
      public final float value;

      Float(float value) {
        this.value = value;
      }
    }

    public static final class Double extends Tag {
      public final double value;

      Double(double value) {
        this.value = value;
      }
    }

    public static final class Str extends Tag {
      public final String value;

      Str(String value) {
        this.value = value;
      }

      @Override
      public String stringValue(String fallback) {
        return value == null ? fallback : value;
      }
    }

    public static final class ByteArray extends Tag {
      public final byte[] value;

      ByteArray(byte[] value) {
        this.value = value;
      }
    }

    public static final class IntArray extends Tag {
      public final int[] value;

      IntArray(int[] value) {
        this.value = value;
      }
    }

    public static final class LongArray extends Tag {
      public final long[] value;

      LongArray(long[] value) {
        this.value = value;
      }
    }

public static final class TagList extends Tag {
      public final int elementId;
      public final java.util.List<Tag> value;

      TagList(int elementId, java.util.List<Tag> value) {
        this.elementId = elementId;
        this.value = value;
      }
    }
  }

  /** Named map of tags - the mutable container every compound is written as. */
  public static final class Compound extends Tag {
    private final Map<String, Tag> entries = new HashMap<>();

    Compound() {
      super(null);
    }

    Tag put(Tag tag) {
      entries.put(tag.name, tag);
      return tag;
    }

    public boolean has(String key) {
      return entries.containsKey(key);
    }

    public Tag get(String key) {
      return entries.get(key);
    }

    public Compound compound(String key) {
      Tag t = entries.get(key);
      return t instanceof Compound c ? c : null;
    }

    public TagList list(String key) {
      Tag t = entries.get(key);
      return t instanceof TagList l ? l : null;
    }

    public long[] longArray(String key) {
      Tag t = entries.get(key);
      return t instanceof LongArray a ? a.value : null;
    }

    public String string(String key, String fallback) {
      Tag t = entries.get(key);
      return t == null ? fallback : t.stringValue(fallback);
    }

    public int integer(String key, int fallback) {
      Tag t = entries.get(key);
      return t == null ? fallback : t.intValue(fallback);
    }

    public long longv(String key, long fallback) {
      Tag t = entries.get(key);
      return t == null ? fallback : t.longValue(fallback);
    }

    public boolean bool(String key, boolean fallback) {
      Tag t = entries.get(key);
      return t == null ? fallback : t.boolValue(fallback);
    }
  }

  /**
   * Unpacks one value from a {@code long[]} bit stream where value {@code index}
   * is packed starting at bit {@code index * bits} in LSB-first order (the
   * Minecraft block/biome palette encoding). Extends into the next long when a
   * value straddles a 64-bit boundary.
   */
  public static int unpackPacked(long[] data, int bits, int index, boolean lenient) {
    if (data == null || data.length == 0) {
      return 0;
    }
    long start = (long) index * bits;
    int longIdx = (int) (start >>> 6);
    int shift = (int) (start & 63);
    if (longIdx >= data.length) {
      return 0;
    }
    long v = data[longIdx] >>> shift;
    if (shift + bits > 64 && longIdx + 1 < data.length) {
      v |= data[longIdx + 1] << (64 - shift);
    }
    long mask = bits >= 64 ? -1L : (1L << bits) - 1L;
    if (lenient && bits >= 64 && shift == 0) {
      // a 64-bit value can't be represented in a long[] cell; treat as corrupt
      return 0;
    }
    return (int) (v & mask);
  }

  /** Decodes a 9-bit-per-entry heightmap (256 entries), mirroring the packing above. */
  public static int[] unpackHeightmap(long[] data) {
    int[] out = new int[256];
    if (data == null || data.length == 0) {
      return out;
    }
    for (int i = 0; i < 256; i++) {
      out[i] = unpackPacked(data, 9, i, false);
    }
    return out;
  }

  /** Strips state-style properties ({@code minecraft:grass_block[snowy=true]}) to the base id. */
  public static String baseId(String paletteEntry) {
    if (paletteEntry == null) {
      return "";
    }
    String s = paletteEntry;
    int bracket = s.indexOf('[');
    if (bracket >= 0) {
      s = s.substring(0, bracket);
    }
    int colon = s.indexOf(':');
    return s.substring(colon + 1);
  }

  /** Pulls a boolean property value out of a state string, or the fallback. */
  public static boolean property(String paletteEntry, String key, boolean fallback) {
    if (paletteEntry == null) {
      return fallback;
    }
    int bracket = paletteEntry.indexOf('[');
    if (bracket < 0) {
      return fallback;
    }
    int close = paletteEntry.indexOf(']', bracket);
    if (close < 0) {
      close = paletteEntry.length();
    }
    String props = paletteEntry.substring(bracket + 1, close);
    for (String pair : props.split(",")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).trim().equals(key)) {
        return pair.substring(eq + 1).trim().equals("true");
      }
    }
    return fallback;
  }

  /** Default text encoding used when a caller hands us a raw string. */
  public static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}


