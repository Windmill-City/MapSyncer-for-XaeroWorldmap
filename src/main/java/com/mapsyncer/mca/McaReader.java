package com.mapsyncer.mca;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McaReader implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(McaReader.class);

    private static final int SECTOR_SIZE = 4096;

    private static final int CHUNKS_PER_REGION = 32;

    private static final int COMPRESS_GZIP = 1;

    private static final int COMPRESS_ZLIB = 2;

    private static final int COMPRESS_NONE = 3;

    private static final int LOCATION_COUNT = CHUNKS_PER_REGION * CHUNKS_PER_REGION;

    private static final long HEADER_ONLY_SIZE = (long) SECTOR_SIZE * 2;

    public static boolean hasAnyChunk(Path mcaPath) {
        if (mcaPath == null || !Files.exists(mcaPath)) {
            return false;
        }
        try {
            long size = Files.size(mcaPath);
            if (size == 0 || size <= HEADER_ONLY_SIZE) {
                return false;
            }
        } catch (IOException e) {
            LOGGER.debug("Cannot stat MCA {}: {}", mcaPath, e.getMessage());
            return false;
        }

        try (FileChannel channel = FileChannel.open(mcaPath, StandardOpenOption.READ)) {
            int[] locations = readLocationTable(channel);
            for (int packed : locations) {
                if ((packed >>> 8) > 0 && (packed & 0xFF) > 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            LOGGER.debug("MCA chunk probe failed for {}: {}", mcaPath, e.getMessage());
            return false;
        }
    }

    public record ChunkLocation(int offsetSectors, int sectorCount) {

        public boolean exists() {
            return offsetSectors > 0 && sectorCount > 0;
        }

        public long dataOffset() {
            return (long) offsetSectors * SECTOR_SIZE;
        }
    }

    private final FileChannel channel;

    private final int[] locations;

    private McaReader(FileChannel channel, int[] locations) {
        this.channel = channel;
        this.locations = locations;
    }

    public static McaReader open(String path) throws IOException {
        FileChannel channel = FileChannel.open(Path.of(path), StandardOpenOption.READ);
        try {
            if (channel.size() < SECTOR_SIZE * 2) {
                throw new IOException("MCA file too small: " + channel.size() + " bytes");
            }
            int[] locations = readLocationTable(channel);
            return new McaReader(channel, locations);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    private static int[] readLocationTable(FileChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(SECTOR_SIZE);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) == -1) {
                break;
            }
        }
        buffer.flip();
        int[] locations = new int[LOCATION_COUNT];
        for (int i = 0; i < LOCATION_COUNT; i++) {
            int b0 = buffer.get() & 0xFF;
            int b1 = buffer.get() & 0xFF;
            int b2 = buffer.get() & 0xFF;
            int b3 = buffer.get() & 0xFF;
            int offset = (b0 << 16) | (b1 << 8) | b2;
            locations[i] = (offset << 8) | b3;
        }
        return locations;
    }

    public ChunkLocation getChunkLocation(int localX, int localZ) {
        int index = localX + localZ * CHUNKS_PER_REGION;
        int packed = locations[index];
        return new ChunkLocation(packed >>> 8, packed & 0xFF);
    }

    public byte[] readChunkData(int localX, int localZ) throws IOException {
        ChunkLocation loc = getChunkLocation(localX, localZ);
        if (!loc.exists()) {
            return null;
        }

        long dataOffset = loc.dataOffset();
        if (dataOffset + 5 > channel.size()) {
            return null;
        }

        ByteBuffer header = ByteBuffer.allocate(5);
        channel.position(dataOffset);
        while (header.hasRemaining()) {
            if (channel.read(header) == -1) {
                break;
            }
        }
        header.flip();

        int totalLength = header.getInt();
        if (totalLength <= 1) {
            return null;
        }

        int compressionType = header.get() & 0xFF;

        int dataLength = totalLength - 1;
        ByteBuffer compressed = ByteBuffer.allocate(dataLength);
        while (compressed.hasRemaining()) {
            if (channel.read(compressed) == -1) {
                break;
            }
        }
        if (compressed.hasRemaining()) {
            return null;
        }

        return decompress(compressed.array(), compressionType);
    }

    private byte[] decompress(byte[] data, int compressionType) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        switch (compressionType) {
            case COMPRESS_GZIP:
                try (GZIPInputStream gis = new GZIPInputStream(bais)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = gis.read(buf)) > 0) {
                        baos.write(buf, 0, len);
                    }
                }
                return baos.toByteArray();

            case COMPRESS_ZLIB:
                try (InflaterInputStream iis = new InflaterInputStream(bais)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = iis.read(buf)) > 0) {
                        baos.write(buf, 0, len);
                    }
                }
                return baos.toByteArray();

            case COMPRESS_NONE:
                return data;

            default:
                throw new IOException("Unknown compression type: " + compressionType);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
