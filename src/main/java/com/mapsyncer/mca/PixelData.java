package com.mapsyncer.mca;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One map pixel of a built chunk: the top visible block state at (x, z) plus its
 * absolute height, the height of the topmost transparent block above it and the
 * light level captured at the column.
 */
public record PixelData(BlockState state, short height, short topHeight, byte light) {

    // TODO: biome - ResourceKey<Biome> sampled at topHeight (simplified: from the chunk's own section biome palette)

    public ResourceKey<Biome> biome() {
        return null;
    }

    // TODO: overlays - accumulated transparent blocks (e.g. water) above the pixel, with opacity

    public boolean hasOverlays() {
        return false;
    }
}
