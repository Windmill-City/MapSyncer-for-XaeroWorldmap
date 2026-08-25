package com.mapsyncer.mca;

import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;

public final class McaRegion implements AutoCloseable {

    private static final int CHUNKS = 32;

    private final java.io.RandomAccessFile file;
    private final int regionX;
    private final int regionZ;
    private final int[] offsets = new int[CHUNKS * CHUNKS];

    private McaRegion(java.io.RandomAccessFile file, int regionX, int regionZ) {
        this.file = file;
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    public static McaRegion open(Path path, int regionX, int regionZ) throws IOException {
        // TODO: read the 8KiB header, open RandomAccessFile on the .mca file
        throw new UnsupportedOperationException("not implemented yet");
    }

    private void readHeader() throws IOException {
        // TODO: parse the 1024 sector-offset ints
        throw new UnsupportedOperationException("not implemented yet");
    }

    public CompoundTag readChunk(int chunkX, int chunkZ) throws IOException {
        // TODO: seek to the chunk sector, inflate zlib/gzip payload, NbtIo.read
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public void close() throws IOException {
        // TODO: close the underlying file handle
        throw new UnsupportedOperationException("not implemented yet");
    }
}
