package com.mapsyncer.mca.convert.model;

import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.ChunkSectionParser.BlockState;
import com.mapsyncer.mca.Constants;
import com.mapsyncer.mca.LightMode;
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
    public final int cave;
    public final ChunkDataParser.ChunkInfo[][] chunkGrid;

    public MapRegionData(int minBuildHeight, LightMode lightMode, int cave) {
        this.lightMode = lightMode;
        this.cave = cave;
        blockStates = new BlockState[Constants.REGION_SIZE_BLOCKS][Constants.REGION_SIZE_BLOCKS];
        topBlockY = new int[Constants.REGION_SIZE_BLOCKS][Constants.REGION_SIZE_BLOCKS];
        for (int x = 0; x < Constants.REGION_SIZE_BLOCKS; x++) {
            Arrays.fill(topBlockY[x], -1);
        }
        biomeNames = new String[Constants.REGION_SIZE_BLOCKS][Constants.REGION_SIZE_BLOCKS];
        heightMap = new int[Constants.REGION_SIZE_BLOCKS][Constants.REGION_SIZE_BLOCKS];
        for (int x = 0; x < Constants.REGION_SIZE_BLOCKS; x++) {
            Arrays.fill(heightMap[x], minBuildHeight);
        }
        lightMap = new byte[Constants.REGION_SIZE_BLOCKS][Constants.REGION_SIZE_BLOCKS];
        hasData = new boolean[Constants.REGION_SIZE_BLOCKS][Constants.REGION_SIZE_BLOCKS];
        chunkExists = new boolean[Constants.CHUNKS_PER_REGION][Constants.CHUNKS_PER_REGION];
        overlays = new HashMap<>();
        chunkGrid = new ChunkDataParser.ChunkInfo[Constants.CHUNKS_PER_REGION][Constants.CHUNKS_PER_REGION];
    }

    public boolean hasAnyMapData() {
        for (int x = 0; x < Constants.REGION_SIZE_BLOCKS; x++) {
            for (int z = 0; z < Constants.REGION_SIZE_BLOCKS; z++) {
                if (hasData[x][z]) {
                    return true;
                }
            }
        }
        return false;
    }
}
