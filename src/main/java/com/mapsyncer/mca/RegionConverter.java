package com.mapsyncer.mca;

import com.mapsyncer.mca.convert.RegionConversionPipeline;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegionConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionConverter.class);

    public static final String DEFAULT_BLOCK = "minecraft:air";
    public static final String DEFAULT_BIOME = "minecraft:the_void";

    public static final int REGION_SIZE_BLOCKS = 512;
    public static final int CHUNKS_PER_REGION = 32;
    public static final int BLOCKS_PER_TILE = 16;
    public static final int TILES_PER_TILE_CHUNK = 4;
    public static final int TILE_CHUNKS_PER_REGION = 8;
    public static final int MAJOR_VERSION = 6;
    public static final int MINOR_VERSION = 8;

    public record ConvertedRegion(int regionX, int regionZ, byte[] xaeroData) {}

    public record LayerConvertedRegion(int regionX, int regionZ, byte[] xaeroData) {}

    public record CaveModeParams(int caveStart, int caveDepth) {
        public static final CaveModeParams NONE = new CaveModeParams(Integer.MAX_VALUE, 0);
    }

    public static List<LayerConvertedRegion> convertRegionMulti(
            Path mcaPath, int regionX, int regionZ, BlockPropertyLookup blockLookup) {
        try {
            return RegionConversionPipeline.convertMulti(mcaPath, regionX, regionZ, dimTypeInfo, passes, blockLookup);
        } catch (IOException e) {
            LOGGER.warn("Failed to convert region ({}, {}) multi-pass", regionX, regionZ, e);
            return List.of();
        }
    }
}
