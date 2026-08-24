package com.mapsyncer.mca.convert.io;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.Constants;
import com.mapsyncer.mca.McaReader;
import com.mapsyncer.mca.Plan;
import com.mapsyncer.mca.convert.biome.BiomeFillPass;
import com.mapsyncer.mca.convert.model.MapRegionData;
import com.mapsyncer.mca.convert.scan.ChunkColumnScanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class McaRegionLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(McaRegionLoader.class);

    public record PassMapData(int cave, MapRegionData data) {}

    public static List<PassMapData> load(
            Path mcaPath,
            int minBuildHeight,
            int worldTopY,
            boolean worldHasSkylight,
            BlockPropertyLookup blockLookup,
            Plan plan)
            throws IOException {
        List<Integer> caveStarts = plan.caveStarts();
        if (caveStarts.isEmpty()) {
            return List.of();
        }

        List<PassMapData> results = new ArrayList<>(caveStarts.size());
        for (int caveStart : caveStarts) {
            results.add(new PassMapData(
                    caveStart, new MapRegionData(minBuildHeight, Plan.lightMode(caveStart), caveStart)));
        }

        try (McaReader reader = McaReader.open(mcaPath.toString())) {
            int worldHeightRange = worldTopY - minBuildHeight;
            ChunkDataParser.ChunkInfo[][] chunks = readAllChunks(reader, worldHeightRange);

            for (int localX = 0; localX < Constants.CHUNKS_PER_REGION; localX++) {
                for (int localZ = 0; localZ < Constants.CHUNKS_PER_REGION; localZ++) {
                    ChunkDataParser.ChunkInfo chunkInfo = chunks[localX][localZ];
                    if (chunkInfo == null) {
                        continue;
                    }
                    for (PassMapData passData : results) {
                        int caveStart = passData.cave();
                        ChunkColumnScanner.scan(
                                passData.data(),
                                chunkInfo,
                                minBuildHeight,
                                worldTopY,
                                Plan.lightMode(caveStart),
                                caveStart,
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
                new ChunkDataParser.ChunkInfo[Constants.CHUNKS_PER_REGION][Constants.CHUNKS_PER_REGION];

        for (int localX = 0; localX < Constants.CHUNKS_PER_REGION; localX++) {
            for (int localZ = 0; localZ < Constants.CHUNKS_PER_REGION; localZ++) {
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
