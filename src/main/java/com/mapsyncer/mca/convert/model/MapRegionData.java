package com.mapsyncer.mca.convert.model;

import static com.mapsyncer.mca.RegionConverter.CHUNKS_PER_REGION;
import static com.mapsyncer.mca.RegionConverter.REGION_SIZE_BLOCKS;

import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.ChunkSectionParser.BlockState;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverter.CaveModeParams;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapRegionData {

    public static class OverlayEntry {
        public final BlockState blockState;
        public int opacity;
        public final int light;

        public OverlayEntry(BlockState blockState, int opacity, int light) {
            this.blockState = blockState;
            this.opacity = opacity;
            this.light = light;
        }

        public String blockName() {
            return blockState.name();
        }
    }

    public final BlockState[][] blockStates;
    public final int[][] topBlockY;
    public final String[][] biomeNames;
    public final int[][] heightMap;
    public final byte[][] lightMap;
    public final boolean[][] hasData;
    public final boolean[][] chunkExists;
    public final Map<Integer, List<OverlayEntry>> overlays;
    public final LightMode lightMode;
    public final CaveModeParams caveParams;
    public final ChunkDataParser.ChunkInfo[][] chunkGrid;

    public MapRegionData(int minBuildHeight, LightMode lightMode, CaveModeParams caveParams) {
        this.lightMode = lightMode;
        this.caveParams = caveParams != null ? caveParams : CaveModeParams.NONE;
        blockStates = new BlockState[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
        topBlockY = new int[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
        for (int x = 0; x < REGION_SIZE_BLOCKS; x++) {
            Arrays.fill(topBlockY[x], -1);
        }
        biomeNames = new String[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
        heightMap = new int[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
        for (int x = 0; x < REGION_SIZE_BLOCKS; x++) {
            Arrays.fill(heightMap[x], minBuildHeight);
        }
        lightMap = new byte[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
        hasData = new boolean[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
        chunkExists = new boolean[CHUNKS_PER_REGION][CHUNKS_PER_REGION];
        overlays = new HashMap<>();
        chunkGrid = new ChunkDataParser.ChunkInfo[CHUNKS_PER_REGION][CHUNKS_PER_REGION];
    }

    public boolean hasAnyMapData() {
        for (int x = 0; x < REGION_SIZE_BLOCKS; x++) {
            for (int z = 0; z < REGION_SIZE_BLOCKS; z++) {
                if (hasData[x][z]) {
                    return true;
                }
            }
        }
        return false;
    }
}
