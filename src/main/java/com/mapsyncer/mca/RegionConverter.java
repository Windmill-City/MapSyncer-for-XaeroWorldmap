package com.mapsyncer.mca;

import com.mapsyncer.mca.convert.biome.BiomeResolver;
import com.mapsyncer.mca.convert.io.XaeroBinaryWriter;
import com.mapsyncer.mca.convert.io.XaeroWriter;
import com.mapsyncer.mca.convert.model.MapRegionData;
import com.mapsyncer.mca.convert.scan.ChunkColumnScanner;
import com.mapsyncer.network.RegionData;
import com.mapsyncer.network.RegionRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RegionConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionConverter.class);

    private record PassMapData(int cave, MapRegionData data) {}

    public static void convert(RegionScanner.Region entry, BlockPropertyLookup blockLookup) throws IOException {
        RegionScanner.Bounds bounds = entry.bounds();
        Plan plan = Plan.getPlan(entry.dimId());
        if (plan.caveStarts().isEmpty()) {
            return;
        }

        Path mcaPath = resolveRegionPath(entry.regionDir(), entry.regionX(), entry.regionZ());
        if (mcaPath == null || !Files.exists(mcaPath)) {
            return;
        }

        List<PassMapData> loaded = load(mcaPath, bounds, plan, blockLookup);

        for (PassMapData passData : loaded) {
            MapRegionData regionData = passData.data();
            if (!regionData.hasAnyMapData()) {
                continue;
            }
            byte[] xaeroData = XaeroBinaryWriter.serialize(regionData, bounds.minY(), blockLookup);
            XaeroWriter.writeRegionFile(new RegionData(
                    new RegionRef(entry.dimId(), Plan.caveLayer(passData.cave()), entry.regionX(), entry.regionZ()),
                    xaeroData));
        }
    }

    private static Path resolveRegionPath(Path regionDir, int regionX, int regionZ) {
        Path mcaPath = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        if (Files.exists(mcaPath)) {
            return mcaPath;
        }
        Path mcrPath = regionDir.resolve("r." + regionX + "." + regionZ + ".mcr");
        return Files.exists(mcrPath) ? mcrPath : null;
    }

    private static List<PassMapData> load(
            Path mcaPath, RegionScanner.Bounds bounds, Plan plan, BlockPropertyLookup blockLookup) throws IOException {
        List<Integer> caveStarts = plan.caveStarts();
        if (caveStarts.isEmpty()) {
            return List.of();
        }

        List<PassMapData> results = new ArrayList<>(caveStarts.size());
        for (int caveStart : caveStarts) {
            results.add(
                    new PassMapData(caveStart, new MapRegionData(bounds.minY(), Plan.lightMode(caveStart), caveStart)));
        }

        try (McaReader reader = McaReader.open(mcaPath.toString())) {
            int worldHeightRange = bounds.maxY() - bounds.minY();
            ChunkParser.ChunkInfo[][] chunks = readAllChunks(reader, worldHeightRange);

            for (int localX = 0; localX < Constants.CHUNKS_PER_REGION; localX++) {
                for (int localZ = 0; localZ < Constants.CHUNKS_PER_REGION; localZ++) {
                    ChunkParser.ChunkInfo chunkInfo = chunks[localX][localZ];
                    if (chunkInfo == null) {
                        continue;
                    }
                    for (PassMapData passData : results) {
                        int caveStart = passData.cave();
                        ChunkColumnScanner.scan(
                                passData.data(),
                                chunkInfo,
                                bounds.minY(),
                                bounds.maxY(),
                                Plan.lightMode(caveStart),
                                caveStart,
                                bounds.hasSkylight(),
                                blockLookup);
                    }
                }
            }
        }

        for (PassMapData passData : results) {
            BiomeResolver.fill(passData.data());
        }
        return results;
    }

    private static ChunkParser.ChunkInfo[][] readAllChunks(McaReader reader, int worldHeightRange) throws IOException {
        ChunkParser.ChunkInfo[][] grid =
                new ChunkParser.ChunkInfo[Constants.CHUNKS_PER_REGION][Constants.CHUNKS_PER_REGION];

        for (int localX = 0; localX < Constants.CHUNKS_PER_REGION; localX++) {
            for (int localZ = 0; localZ < Constants.CHUNKS_PER_REGION; localZ++) {
                ChunkParser.ChunkInfo chunkInfo;
                try {
                    byte[] nbtData = reader.readChunkData(localX, localZ);
                    chunkInfo =
                            nbtData == null ? null : ChunkParser.parseChunk(localX, localZ, nbtData, worldHeightRange);
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
