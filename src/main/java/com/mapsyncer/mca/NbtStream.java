package com.mapsyncer.mca;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

final class NbtStream implements AutoCloseable {

    private final DataInputStream in;

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
        int length = in.readInt();
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    long[] readLongArray() throws IOException {
        int length = in.readInt();
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
        return in.readInt();
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
                byte innerType;
                while ((innerType = in.readByte()) != Constants.TAG_END) {
                    in.readUTF();
                    skip(innerType);
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

    @Override
    public void close() throws IOException {
        in.close();
    }
}
