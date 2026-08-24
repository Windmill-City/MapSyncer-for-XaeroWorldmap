package com.mapsyncer.mca;

import com.mapsyncer.mca.ChunkParser.SectionData;
import java.util.List;

public final class BiomeResolver {

    private static final int VOXELS_PER_SECTION = 64;

    public static final class BiomeQuartGrid {

        private final int minSectionY;
        private final String[][] sectionVoxels;

        private BiomeQuartGrid(int minSectionY, String[][] sectionVoxels) {
            this.minSectionY = minSectionY;
            this.sectionVoxels = sectionVoxels;
        }

        public static BiomeQuartGrid build(List<SectionData> sections, int minSectionY, SectionData[] sectionLookup) {
            if (sectionLookup == null || sectionLookup.length == 0) {
                return new BiomeQuartGrid(minSectionY, new String[0][]);
            }

            String[][] grids = new String[sectionLookup.length][];
            for (SectionData section : sections) {
                if (section == null || section.biomePalette().isEmpty()) {
                    continue;
                }
                int idx = section.sectionY() - minSectionY;
                if (idx < 0 || idx >= grids.length) {
                    continue;
                }
                String[] voxels = new String[VOXELS_PER_SECTION];
                if (section.biomePalette().size() == 1) {
                    String only = section.biomePalette().get(0);
                    java.util.Arrays.fill(voxels, only);
                } else {
                    for (int voxelY = 0; voxelY < 4; voxelY++) {
                        for (int voxelZ = 0; voxelZ < 4; voxelZ++) {
                            for (int voxelX = 0; voxelX < 4; voxelX++) {
                                int blockX = voxelX << 2;
                                int blockY = voxelY << 2;
                                int blockZ = voxelZ << 2;
                                int voxelIndex = (voxelY << 4) | (voxelZ << 2) | voxelX;
                                voxels[voxelIndex] = ChunkParser.getBiomeAt(section, blockX, blockY, blockZ, false);
                            }
                        }
                    }
                }
                grids[idx] = voxels;
            }
            return new BiomeQuartGrid(minSectionY, grids);
        }

        public String lookup(int lx, int absoluteY, int lz) {
            int sectionIdx = (absoluteY >> 4) - minSectionY;
            if (sectionIdx < 0 || sectionIdx >= sectionVoxels.length) {
                return null;
            }
            String[] voxels = sectionVoxels[sectionIdx];
            if (voxels == null) {
                return null;
            }
            int localY = absoluteY & 0xF;
            int voxelIndex = ((localY >> 2) << 4) | ((lz >> 2) << 2) | (lx >> 2);
            return voxels[voxelIndex];
        }
    }

    public static String resolve(ChunkParser.ChunkInfo chunk, int lx, int absoluteY, int lz) {
        return resolve(chunk, lx, absoluteY, lz, false);
    }

    public static String resolveAtY(ChunkParser.ChunkInfo chunk, int lx, int absoluteY, int lz) {
        return resolveAtY(chunk, lx, absoluteY, lz, false);
    }

    public static String resolveAtY(
            ChunkParser.ChunkInfo chunk, int lx, int absoluteY, int lz, boolean smoothBoundary) {
        String biome = resolveBiomeAtAbsoluteY(chunk, lx, absoluteY, lz, smoothBoundary);
        if (isValidBiome(biome)) {
            return biome;
        }

        for (SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            int fallbackLy = absoluteY - s.sectionY() * Constants.CHUNK_SIZE;
            if (fallbackLy < 0 || fallbackLy > Constants.CHUNK_SIZE - 1) {
                continue;
            }
            biome = ChunkParser.getBiomeAt(s, lx, fallbackLy, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        for (SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            for (int tryLy = 0; tryLy <= Constants.CHUNK_SIZE - 1; tryLy++) {
                String candidate = ChunkParser.getBiomeAt(s, lx, tryLy, lz, smoothBoundary);
                if (isValidBiome(candidate)) {
                    return candidate;
                }
            }
        }

        return Constants.BIOME_THE_VOID;
    }

    public static String resolve(ChunkParser.ChunkInfo chunk, int lx, int absoluteY, int lz, boolean smoothBoundary) {
        String biome = resolveBiomeAtAbsoluteY(chunk, lx, absoluteY, lz, smoothBoundary);
        if (isValidBiome(biome)) {
            return biome;
        }

        int[][] heightmap = chunk.heightmap();
        if (heightmap != null) {
            int surfaceY = heightmap[lx][lz];
            biome = resolveBiomeAtAbsoluteY(chunk, lx, surfaceY, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        for (SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            int fallbackLy = absoluteY - s.sectionY() * Constants.CHUNK_SIZE;
            if (fallbackLy < 0 || fallbackLy > Constants.CHUNK_SIZE - 1) {
                continue;
            }
            biome = ChunkParser.getBiomeAt(s, lx, fallbackLy, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        for (SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            for (int tryLy = 0; tryLy <= Constants.CHUNK_SIZE - 1; tryLy++) {
                String candidate = ChunkParser.getBiomeAt(s, lx, tryLy, lz, smoothBoundary);
                if (isValidBiome(candidate)) {
                    return candidate;
                }
            }
        }

        return Constants.BIOME_THE_VOID;
    }

    private static String resolveBiomeAtAbsoluteY(
            ChunkParser.ChunkInfo chunk, int lx, int absoluteY, int lz, boolean smoothBoundary) {
        if (!smoothBoundary && chunk.biomeGrid() != null) {
            String gridBiome = chunk.biomeGrid().lookup(lx, absoluteY, lz);
            if (isValidBiome(gridBiome)) {
                return gridBiome;
            }
        }

        String biome = ChunkParser.getBiomeAt(chunk, lx, absoluteY, lz, smoothBoundary);
        if (isValidBiome(biome)) {
            return biome;
        }

        int targetSectionY = absoluteY >> 4;
        int localY = absoluteY & 0xF;

        for (SectionData s : chunk.sections()) {
            if (s.sectionY() != targetSectionY || s.biomePalette().isEmpty()) {
                continue;
            }
            biome = ChunkParser.getBiomeAt(s, lx, localY, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        for (SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            int fallbackLy = absoluteY - s.sectionY() * Constants.CHUNK_SIZE;
            if (fallbackLy < 0 || fallbackLy > Constants.CHUNK_SIZE - 1) {
                continue;
            }
            biome = ChunkParser.getBiomeAt(s, lx, fallbackLy, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        return null;
    }

    public static void fill(MapRegionData data) {
        for (int rx = 0; rx < Constants.REGION_SIZE_BLOCKS; rx++) {
            for (int rz = 0; rz < Constants.REGION_SIZE_BLOCKS; rz++) {
                int chunkX = rx >> 4;
                int chunkZ = rz >> 4;
                if (chunkX >= Constants.CHUNKS_PER_REGION || chunkZ >= Constants.CHUNKS_PER_REGION) {
                    continue;
                }

                ChunkParser.ChunkInfo chunk = data.chunkGrid[chunkX][chunkZ];
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

                String biome = caveMode ? resolveAtY(chunk, lx, sampleY, lz) : resolve(chunk, lx, sampleY, lz);
                if (isValidBiome(biome)) {
                    data.biomeNames[rx][rz] = biome;
                }
            }
        }
    }

    static boolean isValidBiome(String biome) {
        return biome != null && !biome.equals(Constants.BIOME_THE_VOID);
    }
}
