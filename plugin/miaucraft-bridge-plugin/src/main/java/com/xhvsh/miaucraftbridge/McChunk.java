package com.xhvsh.miaucraftbridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McChunk {
    private static final int BIOME_GRID = 4;
    private static final int SURFACE_OFFSET = 12;

    private McChunk() {
    }

    private static final class Section {
        int y;
        boolean hasBlocks;
        List<String> palette;
        long[] data;
    }

    /**
     * Returns the dominant biome name of the top biomes layer in a decoded
     * (post-compression) 1.18+ chunk NBT payload, or null if it cannot be
     * determined (legacy chunk, corrupted data, or no block-bearing section).
     */
    static String surfaceBiome(byte[] nbt) throws Exception {
        McNbt n = new McNbt(nbt);
        if (n.readByte() != McNbt.TAG_COMPOUND) {
            return null;
        }
        n.readString();
        List<Section> sections = readSections(n);
        if (sections == null || sections.isEmpty()) {
            return null;
        }
        Section top = null;
        for (Section s : sections) {
            if (s.hasBlocks && (top == null || s.y > top.y)) {
                top = s;
            }
        }
        if (top == null || top.palette == null || top.palette.isEmpty() || top.data == null || top.data.length == 0) {
            return null;
        }
        return dominant(top);
    }

    private static List<Section> readSections(McNbt n) throws Exception {
        List<Section> sections = null;
        while (true) {
            byte t = n.readByte();
            if (t == McNbt.TAG_END) {
                break;
            }
            String name = n.readString();
            if (t == McNbt.TAG_LIST && (name.equals("sections") || name.equals("Sections"))) {
                sections = readSectionList(n);
            } else if (t == McNbt.TAG_COMPOUND && name.equals("Level")) {
                List<Section> inner = readSections(n);
                if (inner != null) {
                    sections = inner;
                }
            } else {
                n.skip(t);
            }
        }
        return sections;
    }

    private static List<Section> readSectionList(McNbt n) throws Exception {
        byte elemType = n.readByte();
        int count = n.readInt();
        List<Section> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (elemType == McNbt.TAG_COMPOUND) {
                Section s = readSection(n);
                if (s != null) {
                    out.add(s);
                }
            } else {
                n.skip(elemType);
            }
        }
        return out;
    }

    private static Section readSection(McNbt n) throws Exception {
        Section s = new Section();
        while (true) {
            byte t = n.readByte();
            if (t == McNbt.TAG_END) {
                break;
            }
            String name = n.readString();
            if (name.equals("Y")) {
                if (t == McNbt.TAG_BYTE) {
                    s.y = n.readByte();
                } else if (t == McNbt.TAG_INT) {
                    s.y = n.readInt();
                } else {
                    n.skip(t);
                }
            } else if (t == McNbt.TAG_COMPOUND && name.equals("block_states")) {
                s.hasBlocks = true;
                n.skip(t);
            } else if (t == McNbt.TAG_COMPOUND && name.equals("biomes")) {
                readBiomes(n, s);
            } else {
                n.skip(t);
            }
        }
        return s;
    }

    private static void readBiomes(McNbt n, Section s) throws Exception {
        while (true) {
            byte t = n.readByte();
            if (t == McNbt.TAG_END) {
                break;
            }
            String name = n.readString();
            if (t == McNbt.TAG_LIST && name.equals("palette")) {
                s.palette = readPalette(n);
            } else if (t == McNbt.TAG_LONG_ARRAY && name.equals("data")) {
                s.data = readLongArray(n);
            } else {
                n.skip(t);
            }
        }
    }

    private static List<String> readPalette(McNbt n) throws Exception {
        byte elemType = n.readByte();
        int count = n.readInt();
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (elemType == McNbt.TAG_STRING) {
                out.add(n.readString());
            } else if (elemType == McNbt.TAG_COMPOUND) {
                String value = null;
                while (true) {
                    byte t = n.readByte();
                    if (t == McNbt.TAG_END) {
                        break;
                    }
                    String name = n.readString();
                    if (name.equals("Name") && t == McNbt.TAG_STRING) {
                        value = n.readString();
                    } else {
                        n.skip(t);
                    }
                }
                out.add(value != null ? value : "");
            } else {
                for (int j = 0; j < count - i; j++) {
                    n.skip(elemType);
                }
                break;
            }
        }
        return out;
    }

    private static long[] readLongArray(McNbt n) throws Exception {
        int len = n.readInt();
        if (len < 0 || len > 8192) {
            throw new IllegalStateException("implausible long array length " + len);
        }
        long[] out = new long[len];
        for (int i = 0; i < len; i++) {
            out[i] = n.readLong();
        }
        return out;
    }

    private static String dominant(Section s) {
        int size = s.palette.size();
        int bits = 64 - Long.numberOfLeadingZeros(size - 1);
        if (bits < 1) {
            bits = 1;
        }
        long mask = (1L << bits) - 1;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int zz = 0; zz < BIOME_GRID; zz++) {
            for (int xx = 0; xx < BIOME_GRID; xx++) {
                int entry = SURFACE_OFFSET + zz * BIOME_GRID + xx;
                int pi = paletteIndex(s.data, entry, bits, mask);
                if (pi < 0 || pi >= size) {
                    pi = 0;
                }
                String biome = s.palette.get(pi);
                if (biome == null || biome.isEmpty()) {
                    continue;
                }
                counts.merge(biome, 1, Integer::sum);
            }
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static int paletteIndex(long[] data, int entry, int bits, long mask) {
        int bitPos = entry * bits;
        int longIndex = bitPos >>> 6;
        int offset = bitPos & 63;
        long value;
        if (offset + bits <= 64) {
            value = longIndex < data.length ? (data[longIndex] >>> offset) & mask : 0;
        } else {
            long v0 = longIndex < data.length ? data[longIndex] : 0;
            long v1 = longIndex + 1 < data.length ? data[longIndex + 1] : 0;
            value = ((v0 >>> offset) | (v1 << (64 - offset))) & mask;
        }
        return (int) value;
    }
}