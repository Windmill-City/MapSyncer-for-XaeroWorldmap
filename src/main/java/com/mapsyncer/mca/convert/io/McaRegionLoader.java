package com.mapsyncer.mca.convert.io;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.McaReader;
import com.mapsyncer.mca.RegionConverter;
import com.mapsyncer.mca.convert.biome.BiomeFillPass;
import com.mapsyncer.mca.convert.model.MapRegionData;
import com.mapsyncer.mca.convert.scan.ChunkColumnScanner;
import com.mapsyncer.mca.convert.scan.RegionScanPass;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McaRegionLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(McaRegionLoader.class);

    public record PassMapData(RegionScanPass pass, MapRegionData data) {}

    public static List<PassMapData> loadMulti(
            Path mcaPath,
            int minBuildHeight,
            int worldTopY,
            boolean worldHasSkylight,
            BlockPropertyLookup blockLookup,
            List<RegionScanPass> passes)
            throws IOException {
        if (passes.isEmpty()) {
            return List.of();
        }

        List<PassMapData> results = new ArrayList<>(passes.size());
        for (RegionScanPass pass : passes) {
            results.add(new PassMapData(pass, new MapRegionData(minBuildHeight, pass.lightMode(), pass.caveParams())));
        }

        try (McaReader reader = McaReader.open(mcaPath.toString())) {
            int worldHeightRange = worldTopY - minBuildHeight;
            ChunkDataParser.ChunkInfo[][] chunks = readAllChunks(reader, worldHeightRange);

            for (int localX = 0; localX < RegionConverter.CHUNKS_PER_REGION; localX++) {
                for (int localZ = 0; localZ < RegionConverter.CHUNKS_PER_REGION; localZ++) {
                    ChunkDataParser.ChunkInfo chunkInfo = chunks[localX][localZ];
                    if (chunkInfo == null) {
                        continue;
                    }
                    for (PassMapData passData : results) {
                        RegionScanPass pass = passData.pass();
                        ChunkColumnScanner.scan(
                                passData.data(),
                                chunkInfo,
                                minBuildHeight,
                                worldTopY,
                                pass.lightMode(),
                                pass.caveParams(),
                                worldHasSkylight,
                                blockLookup);
                    }
                }
            }
        }

        for (PassMapData passData : results) {
            BiomeFillPass.fill(passData.data());
        }
        return results;
    }

    private static ChunkDataParser.ChunkInfo[][] readAllChunks(McaReader reader, int worldHeightRange)
            throws IOException {
        ChunkDataParser.ChunkInfo[][] grid =
                new ChunkDataParser.ChunkInfo[RegionConverter.CHUNKS_PER_REGION][RegionConverter.CHUNKS_PER_REGION];

        for (int localX = 0; localX < RegionConverter.CHUNKS_PER_REGION; localX++) {
            for (int localZ = 0; localZ < RegionConverter.CHUNKS_PER_REGION; localZ++) {
                ChunkDataParser.ChunkInfo chunkInfo;
                try {
                    byte[] nbtData = reader.readChunkData(localX, localZ);
                    chunkInfo = nbtData == null
                            ? null
                            : ChunkDataParser.parseChunk(localX, localZ, nbtData, worldHeightRange);
                } catch (Throwable t) {
                    LOGGER.warn(
                            "Failed to read chunk ({}, {}) from region file, skipping: {}",
                            localX,
                            localZ,
                            t.getMessage());
                    continue;
                }
                if (chunkInfo != null) {
                    grid[localX][localZ] = chunkInfo;
                }
            }
        }
        return grid;
    }
}
