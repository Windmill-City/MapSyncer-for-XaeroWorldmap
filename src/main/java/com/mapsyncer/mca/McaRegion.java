package com.mapsyncer.mca;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

public final class McaRegion implements AutoCloseable {

    private static final int CHUNKS = 32;

    private static final int SECTOR_LENGTH = 4096;

    private static final int COMPRESSION_GZIP = 1;

    private static final int COMPRESSION_ZLIB = 2;

    private static final int COMPRESSION_NONE = 3;

    private final RandomAccessFile file;

    private final int[] locations = new int[CHUNKS * CHUNKS];

    private McaRegion(RandomAccessFile file) throws IOException {
        this.file = file;
        for (int i = 0; i < locations.length; i++) {
            locations[i] = file.readInt();
        }
    }

    public static McaRegion open(Path path) throws IOException {
        RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
        boolean success = false;
        try {
            McaRegion region = new McaRegion(file);
            success = true;
            return region;
        } finally {
            if (!success) {
                file.close();
            }
        }
    }

    public CompoundTag readChunk(int chunkX, int chunkZ) throws IOException {
        int index = (chunkZ & (CHUNKS - 1)) * CHUNKS + (chunkX & (CHUNKS - 1));
        int location = locations[index];
        int sectorOffset = location >>> 8;
        if (sectorOffset == 0) {
            return null;
        }
        int sectorCount = location & 0xFF;
        file.seek((long) sectorOffset * SECTOR_LENGTH);
        int length = file.readInt();
        if (length <= 0 || length > sectorCount * SECTOR_LENGTH) {
            throw new IOException("Invalid chunk length " + length + " for chunk (" + chunkX + ", " + chunkZ + ")");
        }
        int compression = file.readByte() & 0xFF;
        byte[] payload = new byte[length - 1];
        file.readFully(payload);
        try (InputStream input = decompress(payload, compression)) {
            return NbtIo.read(new DataInputStream(input));
        }
    }

    private static InputStream decompress(byte[] payload, int compression) throws IOException {
        InputStream input = new ByteArrayInputStream(payload);
        switch (compression) {
            case COMPRESSION_GZIP:
                return new GZIPInputStream(input);
            case COMPRESSION_ZLIB:
                return new InflaterInputStream(input);
            case COMPRESSION_NONE:
                return input;
            default:
                throw new IOException("Unknown chunk compression type " + compression);
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
