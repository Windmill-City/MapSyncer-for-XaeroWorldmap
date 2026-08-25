package com.mapsyncer.mca;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Builds the raw {@code region.xaero} payload for one map region and one layer,
 * byte-compatible with Xaero WorldMap's region save format (major 6, minor 8).
 * {@code cave} is {@link RegionRef#SURFACE_CAVE} for the surface layer or an
 * absolute Y for a cave layer; the scan window is derived from it.
 */
final class RegionBuilder {

    private static final int CAVE_DEPTH = 30;

    private static final int SAVE_MAJOR_VERSION = 6;
    private static final int SAVE_MINOR_VERSION = 8;
    private static final int FULL_VERSION = (SAVE_MAJOR_VERSION << 16) | SAVE_MINOR_VERSION;
    private static final int WORLD_INTERPRETATION_VERSION = 1;

    private static final int FLAG_HAS_STATE = 1;
    private static final int FLAG_HAS_OVERLAYS = 2;
    private static final int FLAG_LIGHT_SHIFT = 8;
    private static final int FLAG_HEIGHT_LOW_SHIFT = 12;
    private static final int FLAG_HAS_BIOME = 0x100000;
    private static final int FLAG_STATE_NOT_IN_PALETTE = 0x200000;
    private static final int FLAG_BIOME_NOT_IN_PALETTE = 0x400000;
    private static final int FLAG_TOP_HEIGHT_DIFFERS = 0x1000000;
    private static final int FLAG_HEIGHT_HIGH_SHIFT = 25;

    private static final int FLAG_OVERLAY_HAS_STATE = 1;
    private static final int FLAG_OVERLAY_LIGHT_SHIFT = 4;
    private static final int FLAG_OVERLAY_STATE_NOT_IN_PALETTE = 0x400;
    private static final int FLAG_OVERLAY_OPACITY_SHIFT = 11;

    static byte[] build(ServerLevel level, Path regionFile, int regionX, int regionZ, int cave) throws IOException {
        Map<BlockState, Integer> statePalette = new HashMap<>();
        Map<ResourceKey<Biome>, Integer> biomePalette = new HashMap<>();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (McaRegion region = McaRegion.open(regionFile);
                DataOutputStream out = new DataOutputStream(buffer)) {
            out.write(0xFF);
            out.writeInt(FULL_VERSION);
            for (int o = 0; o < 8; o++) {
                for (int p = 0; p < 8; p++) {
                    writeTileChunk(out, region, o, p, level, regionX, regionZ, cave, statePalette, biomePalette);
                }
            }
        }
        return buffer.toByteArray();
    }

    private static void writeTileChunk(
            DataOutputStream out,
            McaRegion region,
            int o,
            int p,
            ServerLevel level,
            int regionX,
            int regionZ,
            int cave,
            Map<BlockState, Integer> statePalette,
            Map<ResourceKey<Biome>, Integer> biomePalette)
            throws IOException {
        ChunkBuilder.PixelData[][][][] tiles = new ChunkBuilder.PixelData[4][4][][];
        boolean hasAny = false;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                tiles[i][j] = readTile(region, o, p, i, j, level, regionX, regionZ, cave);
                if (tiles[i][j] != null) {
                    hasAny = true;
                }
            }
        }
        if (!hasAny) {
            return;
        }
        out.write((o << 4) | p);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                ChunkBuilder.PixelData[][] tile = tiles[i][j];
                if (tile == null) {
                    out.writeInt(-1);
                    continue;
                }
                for (int x = 0; x < 16; x++) {
                    ChunkBuilder.PixelData[] column = tile[x];
                    for (int z = 0; z < 16; z++) {
                        savePixel(out, column[z], statePalette, biomePalette);
                    }
                }
                out.write(WORLD_INTERPRETATION_VERSION);
                out.writeInt(cave);
                out.write(CAVE_DEPTH);
            }
        }
    }

    private static ChunkBuilder.PixelData[][] readTile(
            McaRegion region, int o, int p, int i, int j, ServerLevel level, int regionX, int regionZ, int cave)
            throws IOException {
        int chunkX = regionX * 32 + o * 4 + i;
        int chunkZ = regionZ * 32 + p * 4 + j;
        CompoundTag tag = region.readChunk(chunkX, chunkZ);
        if (tag == null) {
            return null;
        }
        int dataVersion = tag.contains("DataVersion", 99) ? tag.getInt("DataVersion") : -1;
        tag = DataFixTypes.CHUNK.updateToCurrentVersion(level.getServer().getFixerUpper(), tag, dataVersion);
        return ChunkBuilder.build(
                tag,
                chunkX,
                chunkZ,
                cave,
                CAVE_DEPTH,
                level,
                level.registryAccess().lookupOrThrow(Registries.BLOCK));
    }

    private static void savePixel(
            DataOutputStream out,
            ChunkBuilder.PixelData pixel,
            Map<BlockState, Integer> statePalette,
            Map<ResourceKey<Biome>, Integer> biomePalette)
            throws IOException {
        boolean isGrass = pixel.state().getBlock() == Blocks.GRASS_BLOCK;
        boolean inPalette = false;
        boolean biomeInPalette = false;
        BlockState state = pixel.state();
        int parametres = pixelParametres(pixel);
        if (!isGrass && !(inPalette = statePalette.containsKey(state))) {
            parametres |= FLAG_STATE_NOT_IN_PALETTE;
        }
        ResourceKey<Biome> pixelBiome = pixel.biome();
        String pixelBiomeString = null;
        if (pixelBiome != null && !(biomeInPalette = biomePalette.containsKey(pixelBiome))) {
            parametres |= FLAG_BIOME_NOT_IN_PALETTE;
            pixelBiomeString = pixelBiome.location().toString();
        }
        out.writeInt(parametres);
        if (!isGrass) {
            if (inPalette) {
                out.writeInt(statePalette.get(state));
            } else {
                NbtIo.write(NbtUtils.writeBlockState(state), out);
                statePalette.put(state, statePalette.size());
            }
        }
        if ((parametres & FLAG_TOP_HEIGHT_DIFFERS) != 0) {
            out.write(pixel.topHeight());
        }
        if (pixel.hasOverlays()) {
            out.write(pixel.overlays().size());
            for (ChunkBuilder.PixelData.Overlay overlay : pixel.overlays()) {
                saveOverlay(out, overlay, statePalette);
            }
        }
        if (pixelBiome != null) {
            if (biomeInPalette) {
                out.writeInt(biomePalette.get(pixelBiome));
            } else {
                out.writeUTF(pixelBiomeString);
                biomePalette.put(pixelBiome, biomePalette.size());
            }
        }
    }

    private static void saveOverlay(
            DataOutputStream out, ChunkBuilder.PixelData.Overlay overlay, Map<BlockState, Integer> statePalette)
            throws IOException {
        boolean isWater = overlay.state().getBlock() == Blocks.WATER;
        boolean inPalette = false;
        BlockState state = overlay.state();
        int parametres = overlayParametres(overlay);
        if (!isWater && !(inPalette = statePalette.containsKey(state))) {
            parametres |= FLAG_OVERLAY_STATE_NOT_IN_PALETTE;
        }
        out.writeInt(parametres);
        if (!isWater) {
            if (inPalette) {
                out.writeInt(statePalette.get(state));
            } else {
                NbtIo.write(NbtUtils.writeBlockState(state), out);
                statePalette.put(state, statePalette.size());
            }
        }
    }

    private static int pixelParametres(ChunkBuilder.PixelData pixel) {
        boolean isGrass = pixel.state().getBlock() == Blocks.GRASS_BLOCK;
        int parametres = 0;
        parametres |= !isGrass ? FLAG_HAS_STATE : 0;
        parametres |= pixel.hasOverlays() ? FLAG_HAS_OVERLAYS : 0;
        parametres |= pixel.light() << FLAG_LIGHT_SHIFT;
        parametres |= (pixel.height() & 0xFF) << FLAG_HEIGHT_LOW_SHIFT;
        parametres |= pixel.biome() != null ? FLAG_HAS_BIOME : 0;
        parametres |= pixel.height() != pixel.topHeight() ? FLAG_TOP_HEIGHT_DIFFERS : 0;
        parametres |= (pixel.height() >> 8 & 0xF) << FLAG_HEIGHT_HIGH_SHIFT;
        return parametres;
    }

    private static int overlayParametres(ChunkBuilder.PixelData.Overlay overlay) {
        boolean isWater = overlay.state().getBlock() == Blocks.WATER;
        int parametres = 0;
        parametres |= !isWater ? FLAG_OVERLAY_HAS_STATE : 0;
        parametres |= overlay.light() << FLAG_OVERLAY_LIGHT_SHIFT;
        parametres |= (overlay.opacity() & 0xF) << FLAG_OVERLAY_OPACITY_SHIFT;
        return parametres;
    }
}
