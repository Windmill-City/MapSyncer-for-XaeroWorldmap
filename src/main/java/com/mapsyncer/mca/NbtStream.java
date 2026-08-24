package com.mapsyncer.mca;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

final class NbtStream implements AutoCloseable {

    private static final int MAX_ARRAY_SIZE = 125_000;

    private static final int MAX_LIST_SIZE = 100_000;

    private static final int MAX_SKIP_DEPTH = 30;

    private final DataInputStream in;

    private int skipDepth = 0;

    NbtStream(InputStream in) {
        this.in = new DataInputStream(in);
    }

    byte readTagType() throws IOException {
        return in.readByte();
    }

    String readString() throws IOException {
        return in.readUTF();
    }

    byte readByte() throws IOException {
        return in.readByte();
    }

    int readInt() throws IOException {
        return in.readInt();
    }

    byte[] readByteArray() throws IOException {
        int length = readArrayLength();
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    long[] readLongArray() throws IOException {
        int length = readArrayLength();
        long[] data = new long[length];
        for (int i = 0; i < length; i++) {
            data[i] = in.readLong();
        }
        return data;
    }

    byte readListElementType() throws IOException {
        return in.readByte();
    }

    int readListLength() throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_LIST_SIZE) {
            throw new IOException("NBT size limit exceeded: List length=" + length + " (max " + MAX_LIST_SIZE + ")");
        }
        return length;
    }

    void skip(byte type) throws IOException {
        switch (type) {
            case Constants.TAG_BYTE:
                in.readByte();
                break;
            case Constants.TAG_SHORT:
                in.readShort();
                break;
            case Constants.TAG_INT:
                in.readInt();
                break;
            case Constants.TAG_LONG:
                in.readLong();
                break;
            case Constants.TAG_FLOAT:
                in.readFloat();
                break;
            case Constants.TAG_DOUBLE:
                in.readDouble();
                break;
            case Constants.TAG_BYTE_ARRAY: {
                int length = in.readInt();
                if (length > 0) {
                    in.skipNBytes(length);
                }
                break;
            }
            case Constants.TAG_STRING:
                in.readUTF();
                break;
            case Constants.TAG_LIST: {
                byte elementType = in.readByte();
                int length = in.readInt();
                if (length > 0) {
                    for (int i = 0; i < length; i++) {
                        skip(elementType);
                    }
                }
                break;
            }
            case Constants.TAG_COMPOUND: {
                skipDepth++;
                if (skipDepth > MAX_SKIP_DEPTH) {
                    skipDepth--;
                    throw new IOException("NBT skip depth exceeded: " + skipDepth);
                }
                try {
                    byte innerType;
                    while ((innerType = in.readByte()) != Constants.TAG_END) {
                        in.readUTF();
                        skip(innerType);
                    }
                } finally {
                    skipDepth--;
                }
                break;
            }
            case Constants.TAG_INT_ARRAY: {
                int length = in.readInt();
                if (length > 0) {
                    in.skipNBytes((long) length * 4);
                }
                break;
            }
            case Constants.TAG_LONG_ARRAY: {
                int length = in.readInt();
                if (length > 0) {
                    in.skipNBytes((long) length * 8);
                }
                break;
            }
            default:
                throw new IOException("Unknown NBT type: " + type);
        }
    }

    private int readArrayLength() throws IOException {
        int length = in.readInt();
        checkLength(length);
        return length;
    }

    private static void checkLength(int length) throws IOException {
        if (length < 0 || length > MAX_ARRAY_SIZE) {
            throw new IOException("NBT size limit exceeded: length=" + length + " (max " + MAX_ARRAY_SIZE + ")");
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
