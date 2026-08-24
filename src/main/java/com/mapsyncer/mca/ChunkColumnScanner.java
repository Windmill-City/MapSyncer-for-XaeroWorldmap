package com.mapsyncer.mca;

import com.mapsyncer.mca.ChunkParser.BlockState;
import com.mapsyncer.mca.ChunkParser.SectionData;
import com.mapsyncer.mca.MapRegionData.OverlayEntry;
import java.util.ArrayList;

public final class ChunkColumnScanner {

    private static final BlockState FALLBACK_SINGLE_STATE = new BlockState(Constants.BLOCK_AIR, java.util.Map.of());

    public static final class ColumnScanContext {

        public final boolean[] blockFound = new boolean[256];
        public final boolean[] underair = new boolean[256];

        public final boolean[] shouldEnterGround = new boolean[256];

        @SuppressWarnings({"unchecked", "rawtypes"})
        public final ArrayList<OverlayEntry>[] overlayLists = new ArrayList[256];

        public final int[] topPixelH = new int[256];

        public ColumnScanContext(boolean fullCave) {
            for (int i = 0; i < 256; i++) {
                underair[i] = fullCave;
                shouldEnterGround[i] = fullCave;
                topPixelH[i] = -1;
            }
        }

        void onAir(int pos) {
            underair[pos] = true;
            shouldEnterGround[pos] = false;
        }

        void onFluid(int pos, boolean isCaveMode) {
            if (!isCaveMode || !shouldEnterGround[pos]) {
                underair[pos] = true;
            }
        }

        boolean canProcessCaveBlock(int pos, boolean isCaveMode) {
            return !isCaveMode || underair[pos];
        }

        static boolean hasFluid(BlockState state, BlockPropertyLookup lookup) {
            if (state.isFluid() || state.isWaterlogged()) {
                return true;
            }
            return (lookup.getFlags(state.name()) & BlockPropertyLookup.FLAG_TRANSLUCENT_FLUID) != 0;
        }

        public static int pos(int lx, int lz) {
            return (lz << 4) | lx;
        }
    }

    public static void scan(
            MapRegionData data,
            ChunkParser.ChunkInfo chunk,
            int minBuildHeight,
            int worldTopY,
            boolean isSurface,
            int caveStart,
            boolean worldHasSkylight,
            BlockPropertyLookup blockLookup) {
        int chunkX = chunk.chunkX();
        int chunkZ = chunk.chunkZ();

        data.chunkExists[chunkX][chunkZ] = true;
        data.chunkGrid[chunkX][chunkZ] = chunk;

        int caveDepth = Constants.CAVE_DEPTH;
        boolean isCaveMode = caveStart != Integer.MAX_VALUE;
        boolean fullCave = caveStart == Integer.MIN_VALUE;
        int[][] heightMap = chunk.heightmap();
        int chunkBottomY = chunk.chunkBottomY();

        ColumnScanContext ctx = new ColumnScanContext(fullCave);

        int sectionIndex = 0;
        for (SectionData section : chunk.sections()) {
            if (section.blockPalette().isEmpty()) {
                continue;
            }

            int sectionY = section.sectionY();
            int sectionBaseY = sectionY * Constants.CHUNK_SIZE;
            int sectionTopY = sectionBaseY + (Constants.CHUNK_SIZE - 1);
            int sectionBottomY = sectionBaseY;

            if (sectionTopY < chunkBottomY) {
                continue;
            }

            boolean singlePalette = section.blockPalette().size() == 1 && section.blockData() == null;
            BlockState singleState = singlePalette ? section.blockPalette().get(0) : FALLBACK_SINGLE_STATE;

            for (int lx = 0; lx < Constants.CHUNK_SIZE; lx++) {
                for (int lz = 0; lz < Constants.CHUNK_SIZE; lz++) {
                    int relX = chunkX * Constants.CHUNK_SIZE + lx;
                    int relZ = chunkZ * Constants.CHUNK_SIZE + lz;
                    if (relX >= Constants.REGION_SIZE_BLOCKS || relZ >= Constants.REGION_SIZE_BLOCKS) {
                        continue;
                    }

                    int pos = ColumnScanContext.pos(lx, lz);
                    if (ctx.blockFound[pos]) {
                        continue;
                    }

                    int heightMapValue = heightMap[lx][lz];

                    int scanBottomY;
                    int startY;
                    if (isCaveMode) {
                        startY = caveStart;
                        scanBottomY = Math.max(caveStart - caveDepth, minBuildHeight);
                    } else {
                        startY = ChunkParser.getHeightmapStartY(chunk, lx, lz, worldTopY);
                        scanBottomY = minBuildHeight;
                    }

                    if (startY < scanBottomY) {
                        continue;
                    }

                    if (isCaveMode && sectionTopY > startY) {
                        continue;
                    }
                    if (sectionTopY < scanBottomY) {
                        continue;
                    }

                    int effectiveStartY = computeEffectiveStartY(
                            sectionIndex, startY, worldTopY, isCaveMode, heightMapValue, chunkBottomY, sectionTopY);

                    if (!isCaveMode && effectiveStartY < sectionBottomY) {
                        continue;
                    }

                    PixelColumnProcessor.processColumn(
                            chunk,
                            section,
                            sectionBaseY,
                            lx,
                            lz,
                            relX,
                            relZ,
                            effectiveStartY,
                            scanBottomY,
                            chunkBottomY,
                            heightMapValue,
                            isCaveMode,
                            worldHasSkylight,
                            isSurface,
                            singlePalette,
                            singleState,
                            ctx,
                            data,
                            blockLookup);
                }
            }

            sectionIndex++;
        }
    }

    private static int computeEffectiveStartY(
            int sectionIndex,
            int startY,
            int worldTopY,
            boolean isCaveMode,
            int heightMapValue,
            int chunkBottomY,
            int sectionTopY) {
        int effectiveStartY = startY;
        if (sectionIndex > 0) {
            effectiveStartY = Math.min(startY + 1, worldTopY - 1);
        }
        if (!isCaveMode && heightMapValue < chunkBottomY) {
            effectiveStartY = sectionTopY;
        }
        if (isCaveMode) {
            effectiveStartY = Math.min(effectiveStartY, sectionTopY);
        }
        return effectiveStartY;
    }
}
