package com.mapsyncer.mca;

import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One map pixel of a built chunk: the top visible block state at (x, z) plus its
 * absolute height, the height of the topmost transparent block above it and the
 * light level captured at the column.
 */
public record PixelData(
        BlockState state, short height, short topHeight, byte light, ResourceKey<Biome> biome, List<Overlay> overlays) {

    /** One transparent block stacked above the pixel, e.g. water, with its opacity. */
    public record Overlay(BlockState state, byte opacity) {}

    public boolean hasOverlays() {
        return !overlays.isEmpty();
    }
}
