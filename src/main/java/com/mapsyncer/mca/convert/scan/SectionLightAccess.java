package com.mapsyncer.mca.convert.scan;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.ChunkSectionParser;
import com.mapsyncer.mca.Constants;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.convert.model.MapRegionData.OverlayEntry;
import java.util.List;

public final class SectionLightAccess {

    public static ChunkSectionParser.SectionData findSectionAt(ChunkDataParser.ChunkInfo chunk, int worldY) {
        ChunkSectionParser.SectionData[] lookup = chunk.sectionLookup();
        if (lookup == null) {
            return null;
        }
        int idx = (worldY >> 4) - chunk.minSectionY();
        if (idx >= 0 && idx < lookup.length) {
            return lookup[idx];
        }
        return null;
    }

    public static byte getBlockLightCrossSection(
            ChunkDataParser.ChunkInfo chunk,
            ChunkSectionParser.SectionData currentSection,
            int lx,
            int ly,
            int lz,
            int worldY) {
        int sectionY = worldY >> 4;
        if (sectionY == currentSection.sectionY()) {
            int localY = worldY - (sectionY * Constants.CHUNK_SIZE);
            if (localY >= 0 && localY <= Constants.CHUNK_SIZE - 1) {
                return ChunkSectionParser.getBlockLight(currentSection, lx, localY, lz);
            }
        }
        ChunkSectionParser.SectionData targetSection = findSectionAt(chunk, worldY);
        if (targetSection != null) {
            int localY = worldY - (targetSection.sectionY() * Constants.CHUNK_SIZE);
            return ChunkSectionParser.getBlockLight(targetSection, lx, localY, lz);
        }
        return 0;
    }

    public static byte calculateSurfaceLight(
            ChunkDataParser.ChunkInfo chunk,
            ChunkSectionParser.SectionData currentSection,
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
        ChunkSectionParser.SectionData stateSection = null;
        int worldYSkySectionY = worldY >> 4;
        if (worldYSkySectionY == currentSection.sectionY()) {
            int localY = worldY - (worldYSkySectionY * Constants.CHUNK_SIZE);
            if (localY >= 0 && localY <= Constants.CHUNK_SIZE - 1) {
                skyLight = ChunkSectionParser.getSkyLight(currentSection, lx, localY, lz);
            }
        } else {
            stateSection = findSectionAt(chunk, worldY);
            if (stateSection != null) {
                int localY = worldY - (stateSection.sectionY() * Constants.CHUNK_SIZE);
                skyLight = ChunkSectionParser.getSkyLight(stateSection, lx, localY, lz);
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
        boolean isGlowing = blockLookup.isGlowing(ChunkSectionParser.getBlockStateAt(stateSection, lx, stateLocalY, lz)
                .name());

        return lightMode.calculateEffectiveLight(
                blockLight, skyLight, hasSkyAccess, hasFluidOverlay, isGlowing, worldHasSkylight);
    }
}
