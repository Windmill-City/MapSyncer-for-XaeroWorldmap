package com.mapsyncer.mca;

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

    public static void convert(RegionScanner.Region entry, BlockPropertyLookup blockLookup) throws IOException {
        RegionScanner.Bounds bounds = entry.bounds();
        Plan plan = Plan.getPlan(entry.dimId());
        if (plan.caveLayers().isEmpty()) {
            return;
        }

        Path mcaPath = resolveRegionPath(entry);
        if (mcaPath == null || !Files.exists(mcaPath)) {
            return;
        }

        List<RegionRef> refs = plan.caveLayers().stream()
                .map(caveLayer -> new RegionRef(entry.dimId(), caveLayer, entry.regionX(), entry.regionZ()))
                .toList();
        List<MapRegionData> loaded = load(mcaPath, bounds, refs, blockLookup);

        for (MapRegionData regionData : loaded) {
            if (!regionData.hasAnyMapData()) {
                continue;
            }
            byte[] xaeroData = XaeroBinaryWriter.serialize(regionData, bounds.minY(), blockLookup);
            XaeroWriter.writeRegionFile(new RegionData(regionData.ref, xaeroData));
        }
    }

    private static Path resolveRegionPath(RegionScanner.Region entry) {
        Path mcaPath = entry.regionDir().resolve("r." + entry.regionX() + "." + entry.regionZ() + ".mca");
        Path mcrPath = entry.regionDir().resolve("r." + entry.regionX() + "." + entry.regionZ() + ".mcr");
        if (Files.exists(mcaPath)) {
            return mcaPath;
        }
        return Files.exists(mcrPath) ? mcrPath : null;
    }

    private static List<MapRegionData> load(
            Path mcaPath, RegionScanner.Bounds bounds, List<RegionRef> refs, BlockPropertyLookup blockLookup)
            throws IOException {
        if (refs.isEmpty()) {
            return List.of();
        }

        List<MapRegionData> results = new ArrayList<>(refs.size());
        for (RegionRef ref : refs) {
            results.add(new MapRegionData(bounds.minY(), ref));
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
                    for (MapRegionData data : results) {
                        ChunkColumnScanner.scan(
                                data,
                                chunkInfo,
                                bounds.minY(),
                                bounds.maxY(),
                                data.lightMode(),
                                data.caveStart(),
                                bounds.hasSkylight(),
                                blockLookup);
                    }
                }
            }
        }

        for (MapRegionData data : results) {
            BiomeResolver.fill(data);
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
