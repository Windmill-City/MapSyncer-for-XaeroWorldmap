package com.mapsyncer.mca;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

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

        try (RandomAccessFile raf = new RandomAccessFile(mcaPath.toFile(), "r")) {
            int[] locations = readLocationTable(raf);
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

    private final RandomAccessFile raf;

    private final int[] locations;

    private McaReader(RandomAccessFile raf, int[] locations) {
        this.raf = raf;
        this.locations = locations;
    }

    public static McaReader open(String path) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path, "r");
        try {
            if (raf.length() < SECTOR_SIZE * 2) {
                throw new IOException("MCA文件太小: " + raf.length() + " bytes");
            }
            int[] locations = readLocationTable(raf);
            return new McaReader(raf, locations);
        } catch (IOException e) {
            raf.close();
            throw e;
        }
    }

    private static int[] readLocationTable(RandomAccessFile raf) throws IOException {
        raf.seek(0);
        byte[] raw = new byte[SECTOR_SIZE];
        raf.readFully(raw);
        int[] locations = new int[LOCATION_COUNT];
        for (int i = 0; i < LOCATION_COUNT; i++) {
            int idx = i * 4;
            int offset = ((raw[idx] & 0xFF) << 16) | ((raw[idx + 1] & 0xFF) << 8) | (raw[idx + 2] & 0xFF);
            locations[i] = (offset << 8) | (raw[idx + 3] & 0xFF);
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
        if (dataOffset + 5 > raf.length()) {
            return null;
        }

        raf.seek(dataOffset);

        int totalLength = raf.readInt();
        if (totalLength <= 1) {
            return null;
        }

        int compressionType = raf.readUnsignedByte();

        int dataLength = totalLength - 1;
        byte[] compressedData = new byte[dataLength];
        int read = 0;
        while (read < dataLength) {
            int r = raf.read(compressedData, read, dataLength - read);
            if (r == -1) break;
            read += r;
        }
        if (read != dataLength) {
            return null;
        }

        return decompress(compressedData, compressionType);
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
                throw new IOException("未知压缩类型: " + compressionType);
        }
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}