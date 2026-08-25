package com.mapsyncer.mca;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds the raw {@code region.xaero} payload for one map region and one cave layer,
 * byte-compatible with Xaero WorldMap's region save format (major 6, minor 8).
 */
public final class RegionBuilder {

    public static final int CAVE_DEPTH = 30;

    private static final int SAVE_MAJOR_VERSION = 6;
    private static final int SAVE_MINOR_VERSION = 8;

    private final ServerLevel level;
    private final Path regionFile;
    private final int regionX;
    private final int regionZ;
    private final int caveStart;
    private final int caveDepth;

    private final Map<BlockState, Integer> statePalette = new HashMap<>();
    private final Map<ResourceKey<Biome>, Integer> biomePalette = new HashMap<>();
    private final ChunkBuilder chunkBuilder = new ChunkBuilder();

    public RegionBuilder(ServerLevel level, Path regionFile, int regionX, int regionZ, int caveStart, int caveDepth) {
        this.level = level;
        this.regionFile = regionFile;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.caveStart = caveStart;
        this.caveDepth = caveDepth;
    }

    public byte[] build() throws IOException {
        // TODO: flush server -> header (0xFF + version) -> 8x8 tile chunk records
        throw new UnsupportedOperationException("not implemented yet");
    }

    private void writeTileChunk(DataOutputStream out, McaRegion region, int o, int p) throws IOException {
        // TODO: build 4x4 tiles, write coords byte, per-tile 256 pixels + cave metadata
        throw new UnsupportedOperationException("not implemented yet");
    }

    private PixelData[][] readTile(McaRegion region, int o, int p, int i, int j) throws IOException {
        // TODO: McaRegion.readChunk -> DataFixTypes.CHUNK -> ChunkBuilder.build
        throw new UnsupportedOperationException("not implemented yet");
    }

    private void savePixel(DataOutputStream out, PixelData pixel) throws IOException {
        // TODO: parametres bitfield, state palette, topHeight byte, overlays, biome
        throw new UnsupportedOperationException("not implemented yet");
    }
}
