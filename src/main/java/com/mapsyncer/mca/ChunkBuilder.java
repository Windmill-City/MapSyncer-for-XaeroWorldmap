package com.mapsyncer.mca;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Replicates Xaero WorldMap's {@code WorldDataReader.buildTile} column scan.
 */
final class ChunkBuilder {

    private static final boolean IGNORE_HEIGHTMAPS = false;
    private static final boolean FLOWERS = true;

    private final boolean[] underair = new boolean[256];
    private final boolean[] shouldEnterGround = new boolean[256];
    private final boolean[] blockFound = new boolean[256];
    private final byte[] lightLevels = new byte[256];
    private final byte[] skyLightLevels = new byte[256];
    private final int[] topH = new int[256];
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final List<BlockState> blockStatePalette = new ArrayList<>();
    private SimpleBitStorage heightMapBitArray;
    private SimpleBitStorage blockStatesBitArray;

    private ServerLevel level;
    private int chunkX;
    private int chunkZ;

    PixelData[][] build(
            CompoundTag chunkTag,
            int chunkX,
            int chunkZ,
            int caveStart,
            int caveDepth,
            ServerLevel level,
            HolderGetter<Block> blockLookup) {
        // TODO: status gate -> heightmap decode -> per-section/per-column scan -> PixelData[16][16]
        throw new UnsupportedOperationException("not implemented yet");
    }

    private boolean buildPixel(BlockState state, int x, int h, int z, int pos2d, boolean cave, boolean fullCave) {
        // TODO: fluid / air / underair / fullCave-enter-ground decision, then buildPixelHelp
        throw new UnsupportedOperationException("not implemented yet");
    }

    private boolean buildPixelHelp(
            BlockState state,
            Block b,
            net.minecraft.world.level.material.FluidState fluidFluidState,
            int pos2d,
            int h,
            boolean cave) {
        // TODO: invisible / overlay / hasVanillaColor decision, topH tracking
        throw new UnsupportedOperationException("not implemented yet");
    }

    private boolean isInvisible(BlockState state, Block b) {
        // TODO: RenderShape.INVISIBLE, torch, grass plant, glass, flowers, double plants
        throw new UnsupportedOperationException("not implemented yet");
    }

    private boolean shouldOverlay(net.minecraft.world.level.block.state.StateHolder<?, ?> state) {
        // TODO: air/glass + server-side translucency approximation (water fluids)
        throw new UnsupportedOperationException("not implemented yet");
    }

    private boolean hasVanillaColor(BlockState state) {
        // TODO: state.getMapColor(level, mutablePos).col != 0
        throw new UnsupportedOperationException("not implemented yet");
    }
}
