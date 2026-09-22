package com.xhvsh.miaucraftbridge;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

final class McRegion {
    static final int CHUNKS = 1024;
    private static final int REGION_SIZE = 32;
    private static final int MAX_CHUNK_BYTES = 16 * 1024 * 1024;

    record ChunkRecord(int chunkX, int chunkZ, String biome) {
    }

    private final File file;
    private final int regionX;
    private final int regionZ;
    private final int[] offsets = new int[CHUNKS];
    private final int[] counts = new int[CHUNKS];

    private McRegion(File file, int regionX, int regionZ) {
        this.file = file;
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    int regionX() {
        return regionX;
    }

    int regionZ() {
        return regionZ;
    }

    static McRegion open(File file) throws IOException {
        int x = 0;
        int z = 0;
        String name = file.getName();
        int a = name.indexOf('.') + 1;
        int b = name.indexOf('.', a);
        int c = name.indexOf('.', b + 1);
        if (a > 0 && b > a && c > b) {
            x = Integer.parseInt(name.substring(a, b));
            z = Integer.parseInt(name.substring(b + 1, c));
        }
        McRegion region = new McRegion(file, x, z);
        byte[] header = new byte[4096];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.readFully(header);
        }
        for (int i = 0; i < CHUNKS; i++) {
            int b0 = i * 4;
            region.offsets[i] = ((header[b0] & 0xff) << 16) | ((header[b0 + 1] & 0xff) << 8) | (header[b0 + 2] & 0xff);
            region.counts[i] = header[b0 + 3] & 0xff;
        }
        return region;
    }

    List<ChunkRecord> scan() throws IOException {
        List<ChunkRecord> out = new ArrayList<>(REGION_SIZE * REGION_SIZE);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            for (int i = 0; i < CHUNKS; i++) {
                if (offsets[i] <= 0 || counts[i] <= 0) {
                    continue;
                }
                byte[] nbt = readChunk(raf, i);
                if (nbt == null) {
                    continue;
                }
                int cx = regionX * REGION_SIZE + (i & 31);
                int cz = regionZ * REGION_SIZE + (i >>> 5);
                try {
                    String biome = McChunk.surfaceBiome(nbt);
                    if (biome != null) {
                        out.add(new ChunkRecord(cx, cz, biome));
                    }
                } catch (Exception ignored) {
                    // skip corrupt chunk
                }
            }
        }
        return out;
    }

    /** How many chunks this region actually contains on disk (offsets > 0). */
    int presentChunks() {
        int n = 0;
        for (int i = 0; i < CHUNKS; i++) {
            if (offsets[i] > 0 && counts[i] > 0) {
                n++;
            }
        }
        return n;
    }

    private byte[] readChunk(RandomAccessFile raf, int index) throws IOException {
        long sector = (long) offsets[index] * 4096L;
        raf.seek(sector);
        byte[] lenBuf = new byte[4];
        raf.readFully(lenBuf);
        int length = ((lenBuf[0] & 0xff) << 24) | ((lenBuf[1] & 0xff) << 16) | ((lenBuf[2] & 0xff) << 8) | (lenBuf[3] & 0xff);
        if (length < 2 || length > MAX_CHUNK_BYTES) {
            return null;
        }
        int compression = raf.readUnsignedByte();
        byte[] lump = new byte[length - 1];
        raf.readFully(lump);
        return inflate(lump, compression);
    }

    private static byte[] inflate(byte[] lump, int compression) {
        try {
            switch (compression) {
                case 1 -> {
                    try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(lump))) {
                        return readAll(in);
                    }
                }
                case 2 -> {
                    try (InflaterInputStream in = new InflaterInputStream(new java.io.ByteArrayInputStream(lump), new Inflater())) {
                        return readAll(in);
                    }
                }
                case 3, 4 -> {
                    return lump;
                }
                default -> {
                    return null;
                }
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}