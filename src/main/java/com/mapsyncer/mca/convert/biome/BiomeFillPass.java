package com.mapsyncer.mca.convert.biome;

import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.Constants;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.convert.model.MapRegionData;

public final class BiomeFillPass {

    public static void fill(MapRegionData data) {
        for (int rx = 0; rx < Constants.REGION_SIZE_BLOCKS; rx++) {
            for (int rz = 0; rz < Constants.REGION_SIZE_BLOCKS; rz++) {
                int chunkX = rx >> 4;
                int chunkZ = rz >> 4;
                if (chunkX >= Constants.CHUNKS_PER_REGION || chunkZ >= Constants.CHUNKS_PER_REGION) {
                    continue;
                }

                ChunkDataParser.ChunkInfo chunk = data.chunkGrid[chunkX][chunkZ];
                if (chunk == null) {
                    continue;
                }

                int lx = rx & 0xF;
                int lz = rz & 0xF;
                int[][] heightmap = chunk.heightmap();

                boolean caveMode = data.lightMode == LightMode.CAVE && data.cave != Integer.MAX_VALUE;

                int sampleY;
                if (data.hasData[rx][rz]) {
                    sampleY = data.heightMap[rx][rz];
                } else if (caveMode) {
                    sampleY = data.cave;
                } else if (heightmap != null) {
                    sampleY = heightmap[lx][lz];
                    data.heightMap[rx][rz] = sampleY;
                } else {
                    continue;
                }

                String biome = caveMode
                        ? BiomeQuartResolver.resolveAtY(chunk, lx, sampleY, lz)
                        : BiomeQuartResolver.resolve(chunk, lx, sampleY, lz);
                if (BiomeQuartResolver.isValidBiome(biome)) {
                    data.biomeNames[rx][rz] = biome;
                }
            }
        }
    }
}
