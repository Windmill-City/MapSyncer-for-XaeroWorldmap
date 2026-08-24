package com.mapsyncer.mca.convert.scan;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkParser;
import com.mapsyncer.mca.ChunkParser.BlockState;
import com.mapsyncer.mca.ChunkParser.SectionData;
import com.mapsyncer.mca.Constants;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.convert.io.XaeroBinaryWriter;
import com.mapsyncer.mca.convert.model.MapRegionData;
import com.mapsyncer.mca.convert.model.MapRegionData.OverlayEntry;
import com.mapsyncer.mca.convert.overlay.OverlayAccumulator;
import java.util.ArrayList;
import java.util.List;

public final class PixelColumnProcessor {
    public static boolean processColumn(
            ChunkParser.ChunkInfo chunk,
            SectionData section,
            int sectionBaseY,
            int lx,
            int lz,
            int relX,
            int relZ,
            int effectiveStartY,
            int scanBottomY,
            int chunkBottomY,
            int heightMapValue,
            boolean isCaveMode,
            boolean worldHasSkylight,
            LightMode lightMode,
            boolean singlePalette,
            BlockState singleState,
            ChunkColumnScanner.ColumnScanContext ctx,
            MapRegionData data,
            BlockPropertyLookup blockLookup) {

        int pos = ChunkColumnScanner.ColumnScanContext.pos(lx, lz);
        if (ctx.blockFound[pos]) {
            return false;
        }

        if (singlePalette) {
            if (singleState.isAir()) {
                if (isCaveMode) {
                    ctx.onAir(pos);
                }
                return false;
            }
            if (isCaveMode && ChunkColumnScanner.ColumnScanContext.hasFluid(singleState, blockLookup)) {
                ctx.onFluid(pos, true);
            }
            if (!ctx.canProcessCaveBlock(pos, isCaveMode)) {
                return false;
            }
        }

        int localStartY = Constants.CHUNK_SIZE - 1;
        if (effectiveStartY >= sectionBaseY && effectiveStartY <= sectionBaseY + (Constants.CHUNK_SIZE - 1)) {
            localStartY = effectiveStartY - sectionBaseY;
        } else if (singlePalette) {
            localStartY = Math.min(effectiveStartY - sectionBaseY, Constants.CHUNK_SIZE - 1);
            if (localStartY < 0) {
                localStartY = Constants.CHUNK_SIZE - 1;
            }
        }
        int localScanBottomY = Math.max(0, scanBottomY - sectionBaseY);

        for (int ly = localStartY; ly >= localScanBottomY; ly--) {
            int worldY = sectionBaseY + ly;
            if (worldY < scanBottomY) {
                break;
            }
            if (worldY < chunkBottomY) {
                break;
            }

            BlockState state = singlePalette ? singleState : ChunkParser.getBlockStateAt(section, lx, ly, lz);

            if (state.isAir()) {
                if (isCaveMode) {
                    ctx.onAir(pos);
                }
                continue;
            }

            if (isCaveMode && ChunkColumnScanner.ColumnScanContext.hasFluid(state, blockLookup)) {
                ctx.onFluid(pos, true);
            }

            if (!ctx.canProcessCaveBlock(pos, isCaveMode)) {
                continue;
            }

            String blockName = state.name();
            int flags = blockLookup.getFlags(blockName);
            ArrayList<OverlayEntry> overlays = ctx.overlayLists[pos];

            if ((flags & BlockPropertyLookup.FLAG_WATER_INHERITING) != 0) {
                return finishSurface(
                        chunk,
                        section,
                        lx,
                        ly,
                        lz,
                        relX,
                        relZ,
                        worldY,
                        state,
                        heightMapValue,
                        overlays,
                        ctx,
                        data,
                        blockLookup,
                        lightMode,
                        worldHasSkylight,
                        true);
            }

            if (blockLookup.isWaterloggedSurface(blockName, state.properties())
                    && (flags & BlockPropertyLookup.FLAG_SHOULD_OVERLAY) == 0) {
                return finishSurface(
                        chunk,
                        section,
                        lx,
                        ly,
                        lz,
                        relX,
                        relZ,
                        worldY,
                        state,
                        heightMapValue,
                        overlays,
                        ctx,
                        data,
                        blockLookup,
                        lightMode,
                        worldHasSkylight,
                        false);
            }

            if ((flags & BlockPropertyLookup.FLAG_TRANSLUCENT_FLUID) != 0) {
                addFluidOverlay(chunk, section, lx, ly, lz, worldY, state, overlays, ctx, pos, blockLookup);
                continue;
            }

            if (state.isWaterlogged() && (flags & BlockPropertyLookup.FLAG_SHOULD_OVERLAY) != 0) {
                int aboveWorldY = worldY + 1;
                int waterOpacity = blockLookup.getLightBlock(Constants.BLOCK_WATER);
                byte waterLight = getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
                overlays = ensureOverlayList(ctx, pos, overlays);
                OverlayAccumulator.add(
                        overlays, XaeroBinaryWriter.WATER, worldY, waterOpacity, waterLight, blockLookup);
                int opacity = blockLookup.getLightBlock(blockName);
                byte light = getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
                OverlayAccumulator.add(overlays, state, worldY, opacity, light, blockLookup);
                if (ctx.topPixelH[pos] < 0) {
                    ctx.topPixelH[pos] = worldY;
                }
                continue;
            }

            if ((flags & BlockPropertyLookup.FLAG_SHOULD_OVERLAY) != 0) {
                int opacity = blockLookup.getLightBlock(blockName);
                int aboveWorldY = worldY + 1;
                byte light = getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
                overlays = ensureOverlayList(ctx, pos, overlays);
                OverlayAccumulator.add(overlays, state, worldY, opacity, light, blockLookup);
                if (ctx.topPixelH[pos] < 0) {
                    ctx.topPixelH[pos] = worldY;
                }
                continue;
            }

            if ((flags & BlockPropertyLookup.FLAG_INVISIBLE) != 0) {
                continue;
            }

            if ((flags & BlockPropertyLookup.FLAG_TRANSPARENT) != 0) {
                int opacity = blockLookup.getLightBlock(blockName);
                int aboveWorldY = worldY + 1;
                byte light = getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
                overlays = ensureOverlayList(ctx, pos, overlays);
                OverlayAccumulator.add(overlays, state, worldY, opacity, light, blockLookup);
                if (ctx.topPixelH[pos] < 0) {
                    ctx.topPixelH[pos] = worldY;
                }
                continue;
            }

            int aboveWorldY = worldY + 1;
            byte light = calculateSurfaceLight(
                    chunk,
                    section,
                    lx,
                    ly,
                    lz,
                    aboveWorldY,
                    heightMapValue,
                    overlays,
                    lightMode,
                    worldHasSkylight,
                    blockLookup);
            int topBlockY = ctx.topPixelH[pos] < 0 ? worldY : ctx.topPixelH[pos];
            recordPixelScan(data, state, worldY, topBlockY, light, ctx.overlayLists[pos], relX, relZ);
            ctx.blockFound[pos] = true;
            return true;
        }

        return false;
    }

    private static boolean finishSurface(
            ChunkParser.ChunkInfo chunk,
            SectionData section,
            int lx,
            int ly,
            int lz,
            int relX,
            int relZ,
            int worldY,
            BlockState state,
            int heightMapValue,
            ArrayList<OverlayEntry> overlays,
            ChunkColumnScanner.ColumnScanContext ctx,
            MapRegionData data,
            BlockPropertyLookup blockLookup,
            LightMode lightMode,
            boolean worldHasSkylight,
            boolean useCalculateLight) {

        int pos = ChunkColumnScanner.ColumnScanContext.pos(lx, lz);
        int opacity = blockLookup.getLightBlock(Constants.BLOCK_WATER);
        int aboveWorldY = worldY + 1;
        byte light = useCalculateLight
                ? calculateSurfaceLight(
                        chunk,
                        section,
                        lx,
                        ly,
                        lz,
                        aboveWorldY,
                        heightMapValue,
                        overlays,
                        lightMode,
                        worldHasSkylight,
                        blockLookup)
                : getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
        overlays = ensureOverlayList(ctx, pos, overlays);
        OverlayAccumulator.add(overlays, XaeroBinaryWriter.WATER, worldY, opacity, light, blockLookup);
        int topBlockY = ctx.topPixelH[pos] < 0 ? worldY : ctx.topPixelH[pos];
        recordPixelScan(data, state, worldY, topBlockY, light, ctx.overlayLists[pos], relX, relZ);
        ctx.blockFound[pos] = true;
        return true;
    }

    private static void addFluidOverlay(
            ChunkParser.ChunkInfo chunk,
            SectionData section,
            int lx,
            int ly,
            int lz,
            int worldY,
            BlockState state,
            ArrayList<OverlayEntry> overlays,
            ChunkColumnScanner.ColumnScanContext ctx,
            int pos,
            BlockPropertyLookup blockLookup) {

        int opacity = blockLookup.getLightBlock(state.name());
        int aboveWorldY = worldY + 1;
        byte light = getBlockLightCrossSection(chunk, section, lx, ly, lz, aboveWorldY);
        overlays = ensureOverlayList(ctx, pos, overlays);
        OverlayAccumulator.add(overlays, state, worldY, opacity, light, blockLookup);
        if (ctx.topPixelH[pos] < 0) {
            ctx.topPixelH[pos] = worldY;
        }
    }

    private static ArrayList<OverlayEntry> ensureOverlayList(
            ChunkColumnScanner.ColumnScanContext ctx, int pos, ArrayList<OverlayEntry> overlays) {
        if (overlays == null) {
            overlays = new ArrayList<>();
            ctx.overlayLists[pos] = overlays;
        }
        return overlays;
    }

    static void recordPixelScan(
            MapRegionData data,
            BlockState surfaceState,
            int topY,
            int highestBlockY,
            byte surfaceLight,
            List<OverlayEntry> overlayList,
            int relX,
            int relZ) {
        if (relX >= Constants.REGION_SIZE_BLOCKS || relZ >= Constants.REGION_SIZE_BLOCKS) {
            return;
        }
        data.markData(relX, relZ);
        BlockState stored = surfaceState != null ? surfaceState : XaeroBinaryWriter.AIR;
        data.blockStates[relX][relZ] = stored;
        data.topBlockY[relX][relZ] = highestBlockY;
        data.heightMap[relX][relZ] = topY;
        data.lightMap[relX][relZ] = surfaceLight;
        if (overlayList != null && !overlayList.isEmpty()) {
            data.overlays.put(relX * Constants.REGION_SIZE_BLOCKS + relZ, overlayList);
        }
    }

    private static SectionData findSectionAt(ChunkParser.ChunkInfo chunk, int worldY) {
        SectionData[] lookup = chunk.sectionLookup();
        if (lookup == null) {
            return null;
        }
        int idx = (worldY >> 4) - chunk.minSectionY();
        if (idx >= 0 && idx < lookup.length) {
            return lookup[idx];
        }
        return null;
    }

    private static byte getBlockLightCrossSection(
            ChunkParser.ChunkInfo chunk, SectionData currentSection, int lx, int ly, int lz, int worldY) {
        int sectionY = worldY >> 4;
        if (sectionY == currentSection.sectionY()) {
            int localY = worldY - (sectionY * Constants.CHUNK_SIZE);
            if (localY >= 0 && localY <= Constants.CHUNK_SIZE - 1) {
                return ChunkParser.getBlockLight(currentSection, lx, localY, lz);
            }
        }
        SectionData targetSection = findSectionAt(chunk, worldY);
        if (targetSection != null) {
            int localY = worldY - (targetSection.sectionY() * Constants.CHUNK_SIZE);
            return ChunkParser.getBlockLight(targetSection, lx, localY, lz);
        }
        return 0;
    }

    private static byte calculateSurfaceLight(
            ChunkParser.ChunkInfo chunk,
            SectionData currentSection,
            int lx,
            int ly,
            int lz,
            int worldY,
            int heightMapValue,
            List<OverlayEntry> overlayList,
            LightMode lightMode,
            boolean worldHasSkylight,
            BlockPropertyLookup blockLookup) {
        byte blockLight = getBlockLightCrossSection(chunk, currentSection, lx, ly, lz, worldY);
        byte skyLight = 0;
        SectionData stateSection = null;
        int worldYSkySectionY = worldY >> 4;
        if (worldYSkySectionY == currentSection.sectionY()) {
            int localY = worldY - (worldYSkySectionY * Constants.CHUNK_SIZE);
            if (localY >= 0 && localY <= Constants.CHUNK_SIZE - 1) {
                skyLight = ChunkParser.getSkyLight(currentSection, lx, localY, lz);
            }
        } else {
            stateSection = findSectionAt(chunk, worldY);
            if (stateSection != null) {
                int localY = worldY - (stateSection.sectionY() * Constants.CHUNK_SIZE);
                skyLight = ChunkParser.getSkyLight(stateSection, lx, localY, lz);
            }
        }

        boolean hasFluidOverlay = false;
        if (overlayList != null) {
            for (OverlayEntry o : overlayList) {
                if (blockLookup.isWater(o.blockName())) {
                    hasFluidOverlay = true;
                    break;
                }
            }
        }

        boolean hasSkyAccess = worldY >= heightMapValue;

        if (stateSection == null) {
            stateSection = findSectionAt(chunk, worldY);
        }
        if (stateSection == null) {
            stateSection = currentSection;
        }
        int stateLocalY = worldY - (stateSection.sectionY() * Constants.CHUNK_SIZE);
        if (stateLocalY < 0 || stateLocalY > Constants.CHUNK_SIZE - 1) {
            stateLocalY = ly;
        }
        boolean isGlowing = blockLookup.isGlowing(
                ChunkParser.getBlockStateAt(stateSection, lx, stateLocalY, lz).name());

        return lightMode.calculateEffectiveLight(
                blockLight, skyLight, hasSkyAccess, hasFluidOverlay, isGlowing, worldHasSkylight);
    }
}
