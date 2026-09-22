package com.xhvsh.miaucraftbridge;

import java.nio.charset.StandardCharsets;

final class McNbt {
    static final byte TAG_END = 0;
    static final byte TAG_BYTE = 1;
    static final byte TAG_SHORT = 2;
    static final byte TAG_INT = 3;
    static final byte TAG_LONG = 4;
    static final byte TAG_FLOAT = 5;
    static final byte TAG_DOUBLE = 6;
    static final byte TAG_BYTE_ARRAY = 7;
    static final byte TAG_STRING = 8;
    static final byte TAG_LIST = 9;
    static final byte TAG_COMPOUND = 10;
    static final byte TAG_INT_ARRAY = 11;
    static final byte TAG_LONG_ARRAY = 12;

    private final byte[] buf;
    private int pos;

    McNbt(byte[] buf) {
        this.buf = buf;
        this.pos = 0;
    }

    byte readByte() {
        return buf[pos++];
    }

    int readShort() {
        int v = ((buf[pos] & 0xff) << 8) | (buf[pos + 1] & 0xff);
        pos += 2;
        return (short) v;
    }

    int readInt() {
        int v = ((buf[pos] & 0xff) << 24) | ((buf[pos + 1] & 0xff) << 16)
                | ((buf[pos + 2] & 0xff) << 8) | (buf[pos + 3] & 0xff);
        pos += 4;
        return v;
    }

    long readLong() {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (buf[pos + i] & 0xff);
        }
        pos += 8;
        return v;
    }

    String readString() {
        int len = readShort();
        if (len < 0 || pos + len > buf.length) {
            throw new IllegalStateException("NBT string overruns buffer");
        }
        String s = new String(buf, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    void skip(int type) {
        switch (type) {
            case TAG_BYTE -> pos += 1;
            case TAG_SHORT -> pos += 2;
            case TAG_INT -> pos += 4;
            case TAG_LONG -> pos += 8;
            case TAG_FLOAT -> pos += 4;
            case TAG_DOUBLE -> pos += 8;
            case TAG_BYTE_ARRAY -> {
                int len = readInt();
                pos += len;
            }
            case TAG_INT_ARRAY -> {
                int len = readInt();
                pos += len * 4;
            }
            case TAG_LONG_ARRAY -> {
                int len = readInt();
                pos += len * 8;
            }
            case TAG_STRING -> readString();
            case TAG_LIST -> {
                byte elemType = readByte();
                int len = readInt();
                for (int i = 0; i < len && pos < buf.length; i++) {
                    skip(elemType);
                }
            }
            case TAG_COMPOUND -> skipCompound();
            default -> throw new IllegalStateException("cannot skip unknown NBT tag " + type);
        }
    }

    void skipCompound() {
        while (pos < buf.length) {
            byte t = readByte();
            if (t == TAG_END) {
                return;
            }
            readString();
            skip(t);
        }
    }
}