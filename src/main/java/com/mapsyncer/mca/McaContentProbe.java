package com.mapsyncer.mca;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

public final class McaContentProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(McaContentProbe.class);

    private static final int SECTOR_SIZE = 4096;
    private static final int CHUNKS_PER_REGION = 32;

    private static final long HEADER_ONLY_SIZE = (long) SECTOR_SIZE * 2;

    private McaContentProbe() {}

    public static boolean hasAnyChunk(Path mcaPath) {
        if (mcaPath == null || !Files.exists(mcaPath)) {
            return false;
        }
        try {
            long size = Files.size(mcaPath);
            if (size == 0) {
                return false;
            }
            if (size <= HEADER_ONLY_SIZE) {
                return false;
            }
        } catch (IOException e) {
            LOGGER.debug("Cannot stat MCA {}: {}", mcaPath, e.getMessage());
            return false;
        }

        try (RandomAccessFile raf = new RandomAccessFile(mcaPath.toFile(), "r")) {
            if (raf.length() < HEADER_ONLY_SIZE) {
                return false;
            }
            for (int localX = 0; localX < CHUNKS_PER_REGION; localX++) {
                for (int localZ = 0; localZ < CHUNKS_PER_REGION; localZ++) {
                    int index = (localX + localZ * CHUNKS_PER_REGION) * 4;
                    raf.seek(index);
                    int b0 = raf.readUnsignedByte();
                    int b1 = raf.readUnsignedByte();
                    int b2 = raf.readUnsignedByte();
                    int offsetSectors = (b0 << 16) | (b1 << 8) | b2;
                    int sectorCount = raf.readUnsignedByte();
                    if (offsetSectors > 0 && sectorCount > 0) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            LOGGER.debug("MCA chunk probe failed for {}: {}", mcaPath, e.getMessage());
            return false;
        }
    }
}
