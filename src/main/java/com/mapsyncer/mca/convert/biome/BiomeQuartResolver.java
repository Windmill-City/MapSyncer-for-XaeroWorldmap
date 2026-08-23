package com.mapsyncer.mca.convert.biome;

import static com.mapsyncer.mca.RegionConverter.DEFAULT_BIOME;

import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.ChunkSectionParser;
import javax.annotation.Nullable;

public final class BiomeQuartResolver {

    public static @Nullable String resolve(ChunkDataParser.ChunkInfo chunk, int lx, int absoluteY, int lz) {
        return resolve(chunk, lx, absoluteY, lz, false);
    }

    public static @Nullable String resolveAtY(ChunkDataParser.ChunkInfo chunk, int lx, int absoluteY, int lz) {
        return resolveAtY(chunk, lx, absoluteY, lz, false);
    }

    public static @Nullable String resolveAtY(
            ChunkDataParser.ChunkInfo chunk, int lx, int absoluteY, int lz, boolean smoothBoundary) {
        String biome = resolveBiomeAtAbsoluteY(chunk, lx, absoluteY, lz, smoothBoundary);
        if (isValidBiome(biome)) {
            return biome;
        }

        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            int fallbackLy = absoluteY - s.sectionY() * 16;
            if (fallbackLy < 0 || fallbackLy > 15) {
                continue;
            }
            biome = ChunkSectionParser.getBiomeAt(s, lx, fallbackLy, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            for (int tryLy = 0; tryLy <= 15; tryLy++) {
                String candidate = ChunkSectionParser.getBiomeAt(s, lx, tryLy, lz, smoothBoundary);
                if (isValidBiome(candidate)) {
                    return candidate;
                }
            }
        }

        return DEFAULT_BIOME;
    }

    public static @Nullable String resolve(
            ChunkDataParser.ChunkInfo chunk, int lx, int absoluteY, int lz, boolean smoothBoundary) {
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

        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            int fallbackLy = absoluteY - s.sectionY() * 16;
            if (fallbackLy < 0 || fallbackLy > 15) {
                continue;
            }
            biome = ChunkSectionParser.getBiomeAt(s, lx, fallbackLy, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            for (int tryLy = 0; tryLy <= 15; tryLy++) {
                String candidate = ChunkSectionParser.getBiomeAt(s, lx, tryLy, lz, smoothBoundary);
                if (isValidBiome(candidate)) {
                    return candidate;
                }
            }
        }

        return DEFAULT_BIOME;
    }

    private static @Nullable String resolveBiomeAtAbsoluteY(
            ChunkDataParser.ChunkInfo chunk, int lx, int absoluteY, int lz, boolean smoothBoundary) {
        if (!smoothBoundary && chunk.biomeGrid() != null) {
            String gridBiome = chunk.biomeGrid().lookup(lx, absoluteY, lz);
            if (isValidBiome(gridBiome)) {
                return gridBiome;
            }
        }

        String biome = ChunkDataParser.getBiomeAt(chunk, lx, absoluteY, lz, smoothBoundary);
        if (isValidBiome(biome)) {
            return biome;
        }

        int targetSectionY = absoluteY >> 4;
        int localY = absoluteY & 0xF;

        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s.sectionY() != targetSectionY || s.biomePalette().isEmpty()) {
                continue;
            }
            biome = ChunkSectionParser.getBiomeAt(s, lx, localY, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        for (ChunkSectionParser.SectionData s : chunk.sections()) {
            if (s.biomePalette().isEmpty()) {
                continue;
            }
            int fallbackLy = absoluteY - s.sectionY() * 16;
            if (fallbackLy < 0 || fallbackLy > 15) {
                continue;
            }
            biome = ChunkSectionParser.getBiomeAt(s, lx, fallbackLy, lz, smoothBoundary);
            if (isValidBiome(biome)) {
                return biome;
            }
        }

        return null;
    }

    static boolean isValidBiome(@Nullable String biome) {
        return biome != null && !biome.equals(DEFAULT_BIOME);
    }
}
