package com.mapsyncer.mca.convert.scan;

import static com.mapsyncer.mca.RegionConverterStandalone.REGION_SIZE_BLOCKS;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.ChunkSectionParser;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverterStandalone;
import com.mapsyncer.mca.convert.model.MapRegionData;
import com.mapsyncer.mca.convert.model.MapRegionData.OverlayEntry;
import java.util.ArrayList;

public final class ChunkColumnScanner {

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

        static boolean hasFluid(ChunkSectionParser.BlockState state, BlockPropertyLookup lookup) {
            if (state.isFluid() || state.isWaterlogged()) {
                return true;
            }
            return (lookup.getFlags(state.name()) & BlockPropertyLookup.FLAG_TRANSLUCENT_FLUID) != 0;
        }

        public static int pos(int lx, int lz) {
            return (lz << 4) | lx;
        }
    }

    private ChunkColumnScanner() {}

    public static void scan(
            MapRegionData data,
            ChunkDataParser.ChunkInfo chunk,
            int minBuildHeight,
            int worldTopY,
            LightMode lightMode,
            RegionConverterStandalone.CaveModeParams caveParams,
            boolean worldHasSkylight,
            BlockPropertyLookup blockLookup,
            RegionScanPass.ScanVerticalBounds bounds) {
        int chunkX = chunk.chunkX();
        int chunkZ = chunk.chunkZ();

        data.chunkExists[chunkX][chunkZ] = true;
        data.chunkGrid[chunkX][chunkZ] = chunk;

        int caveStart = caveParams.caveStart();
        int caveDepth = caveParams.caveDepth();
        boolean isCaveMode = caveStart != Integer.MAX_VALUE;
        boolean fullCave = caveStart == Integer.MIN_VALUE;
        int[][] heightMap = chunk.heightmap();
        int chunkBottomY = chunk.chunkBottomY();

        ColumnScanContext ctx = new ColumnScanContext(fullCave);

        int sectionIndex = 0;
        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.blockPalette().isEmpty()) {
                continue;
            }

            int sectionY = section.sectionY();
            int sectionBaseY = sectionY * 16;
            int sectionTopY = sectionBaseY + 15;
            int sectionBottomY = sectionBaseY;

            if (sectionTopY < chunkBottomY) {
                continue;
            }

            boolean singlePalette = section.blockPalette().size() == 1 && section.blockData() == null;
            ChunkSectionParser.BlockState singleState =
                    singlePalette ? section.blockPalette().get(0) : null;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int relX = chunkX * 16 + lx;
                    int relZ = chunkZ * 16 + lz;
                    if (relX >= REGION_SIZE_BLOCKS || relZ >= REGION_SIZE_BLOCKS) {
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
                        startY = bounds.clampStartY(caveStart);
                        scanBottomY =
                                bounds.clampBottomY(minBuildHeight, Math.max(caveStart - caveDepth, minBuildHeight));
                    } else {
                        startY = bounds.resolveSurfaceStartY(
                                ChunkDataParser.getHeightmapStartY(chunk, lx, lz, worldTopY));
                        scanBottomY = bounds.clampBottomY(minBuildHeight, minBuildHeight);
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
                            sectionIndex,
                            startY,
                            worldTopY,
                            isCaveMode,
                            heightMapValue,
                            chunkBottomY,
                            sectionTopY,
                            bounds);

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
                            lightMode,
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
            int sectionTopY,
            RegionScanPass.ScanVerticalBounds bounds) {
        int effectiveStartY = startY;
        if (sectionIndex > 0) {
            effectiveStartY = Math.min(startY + 1, worldTopY - 1);
        }
        if (!isCaveMode && !bounds.ignoresHeightmap() && heightMapValue < chunkBottomY) {
            effectiveStartY = sectionTopY;
        }
        if (isCaveMode) {
            effectiveStartY = Math.min(effectiveStartY, sectionTopY);
        }
        return effectiveStartY;
    }
}
