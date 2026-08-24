package com.mapsyncer.mca.convert.io;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkSectionParser.BlockState;
import com.mapsyncer.mca.Constants;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.convert.model.MapRegionData;
import com.mapsyncer.mca.convert.model.MapRegionData.OverlayEntry;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class XaeroBinaryWriter {

    private static final int BLOCKS_PER_TILE = 16;
    private static final int TILES_PER_TILE_CHUNK = 4;
    private static final int TILE_CHUNKS_PER_REGION = 8;
    private static final int MAJOR_VERSION = 6;
    private static final int MINOR_VERSION = 8;

    public static final BlockState AIR = new BlockState(Constants.BLOCK_AIR, Map.of());
    public static final BlockState WATER = new BlockState(Constants.BLOCK_WATER, Map.of());

    private static final ConcurrentHashMap<BlockState, PaletteKey> PALETTE_KEY_CACHE = new ConcurrentHashMap<>();
    private static final PaletteKey AIR_KEY = PaletteKey.from(AIR);

    private static final int FLAG_HAS_BIOME = 0x100000;
    private static final int FLAG_NEW_BLOCK = 0x200000;
    private static final int FLAG_NEW_BIOME = 0x400000;
    private static final int FLAG_TOP_HEIGHT_DIFF = 0x1000000;

    public record PaletteKey(String name, List<Map.Entry<String, String>> properties) {

        public static PaletteKey from(BlockState state) {
            if (state == null) {
                return from(AIR);
            }
            return PALETTE_KEY_CACHE.computeIfAbsent(state, s -> {
                TreeMap<String, String> sorted = new TreeMap<>(s.properties());
                return new PaletteKey(s.name(), List.copyOf(sorted.entrySet()));
            });
        }
    }

    private static void writeBlockState(BlockState state, DataOutputStream dos) throws IOException {
        BlockState effective = state != null ? state : AIR;
        dos.writeByte(Constants.TAG_COMPOUND);
        dos.writeShort(0);

        dos.writeByte(Constants.TAG_STRING);
        dos.writeUTF(Constants.NBT_KEY_NAME);
        dos.writeUTF(effective.name());

        if (!effective.properties().isEmpty()) {
            dos.writeByte(Constants.TAG_COMPOUND);
            dos.writeUTF(Constants.NBT_KEY_PROPERTIES);
            TreeMap<String, String> sorted = new TreeMap<>(effective.properties());
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                dos.writeByte(Constants.TAG_STRING);
                dos.writeUTF(entry.getKey());
                dos.writeUTF(entry.getValue());
            }
            dos.writeByte(Constants.TAG_END);
        }

        dos.writeByte(Constants.TAG_END);
    }

    public static byte[] serialize(MapRegionData data, int minBuildHeight, BlockPropertyLookup blockLookup)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeByte(0xFF);
            dos.writeInt((MAJOR_VERSION << 16) | MINOR_VERSION);

            Map<PaletteKey, Integer> blockPalette = new LinkedHashMap<>();
            Map<String, Integer> biomePalette = new LinkedHashMap<>();

            for (int tileChunkO = 0; tileChunkO < TILE_CHUNKS_PER_REGION; tileChunkO++) {
                for (int tileChunkP = 0; tileChunkP < TILE_CHUNKS_PER_REGION; tileChunkP++) {
                    dos.writeByte((tileChunkO << 4) | tileChunkP);

                    for (int tileI = 0; tileI < TILES_PER_TILE_CHUNK; tileI++) {
                        for (int tileJ = 0; tileJ < TILES_PER_TILE_CHUNK; tileJ++) {
                            int chunkX = tileChunkO * 4 + tileI;
                            int chunkZ = tileChunkP * 4 + tileJ;

                            int baseX = chunkX * 16;
                            int baseZ = chunkZ * 16;

                            if (!data.chunkExists[chunkX][chunkZ]) {
                                dos.writeInt(-1);
                                continue;
                            }

                            for (int bx = 0; bx < BLOCKS_PER_TILE; bx++) {
                                for (int bz = 0; bz < BLOCKS_PER_TILE; bz++) {
                                    int rx = baseX + bx;
                                    int rz = baseZ + bz;

                                    if (!data.hasData[rx][rz]) {
                                        boolean caveMode = data.lightMode == LightMode.CAVE;
                                        writeEmptyPixel(
                                                dos,
                                                data,
                                                rx,
                                                rz,
                                                caveMode ? minBuildHeight : data.heightMap[rx][rz],
                                                caveMode,
                                                blockPalette,
                                                biomePalette);
                                        continue;
                                    }

                                    writePixel(dos, data, rx, rz, blockPalette, biomePalette, blockLookup);
                                }
                            }

                            dos.writeByte(1);
                            dos.writeInt(data.cave);
                            dos.writeByte(Constants.CAVE_DEPTH & 0xFF);
                        }
                    }
                }
            }
        }
        return baos.toByteArray();
    }

    private static void writeEmptyPixel(
            DataOutputStream dos,
            MapRegionData data,
            int rx,
            int rz,
            int emptyHeight,
            boolean caveMode,
            Map<PaletteKey, Integer> blockPalette,
            Map<String, Integer> biomePalette)
            throws IOException {
        String biomeName = normalizeBiome(data.biomeNames[rx][rz]);

        int emptyParams = 1;
        if (!caveMode) {
            emptyParams |= Constants.MAX_LIGHT_LEVEL << 8;
        }
        emptyParams |= encodeHeightToParams(emptyHeight);
        emptyParams |= encodePaletteFlags(true, blockPalette, AIR_KEY, biomeName, biomePalette);

        dos.writeInt(emptyParams);
        writeBlockStateRef(dos, AIR, blockPalette);
        writeBiomeRef(dos, biomeName, biomePalette);
    }

    private static String normalizeBiome(String biomeName) {
        return (biomeName == null || biomeName.equals(Constants.BIOME_THE_VOID)) ? null : biomeName;
    }

    private static int encodePaletteFlags(
            boolean includeBlock,
            Map<PaletteKey, Integer> blockPalette,
            PaletteKey blockKey,
            String biomeName,
            Map<String, Integer> biomePalette) {
        int flags = 0;
        if (includeBlock && !blockPalette.containsKey(blockKey)) {
            flags |= FLAG_NEW_BLOCK;
        }
        if (biomeName != null) {
            flags |= FLAG_HAS_BIOME;
            if (!biomePalette.containsKey(biomeName)) {
                flags |= FLAG_NEW_BIOME;
            }
        }
        return flags;
    }

    private static void writeBiomeRef(DataOutputStream dos, String biomeName, Map<String, Integer> biomePalette)
            throws IOException {
        if (biomeName == null) {
            return;
        }
        if (biomePalette.containsKey(biomeName)) {
            dos.writeInt(biomePalette.get(biomeName));
        } else {
            dos.writeUTF(biomeName);
            biomePalette.put(biomeName, biomePalette.size());
        }
    }

    private static void writePixel(
            DataOutputStream dos,
            MapRegionData data,
            int rx,
            int rz,
            Map<PaletteKey, Integer> blockPalette,
            Map<String, Integer> biomePalette,
            BlockPropertyLookup blockLookup)
            throws IOException {
        BlockState blockState = data.blockStates[rx][rz];
        if (blockState == null) {
            blockState = new BlockState(Constants.BLOCK_AIR, Map.of());
        }
        String blockName = blockState.name();
        PaletteKey paletteKey = PaletteKey.from(blockState);

        int height = data.heightMap[rx][rz];
        int topY = data.topBlockY[rx][rz];
        int topHeight = (topY >= 0) ? topY : height;
        String biomeName = normalizeBiome(data.biomeNames[rx][rz]);
        int light = data.lightMap[rx][rz];
        List<OverlayEntry> overlays = data.overlays.get(rx * Constants.REGION_SIZE_BLOCKS + rz);
        boolean hasOverlays = overlays != null && !overlays.isEmpty();
        boolean isGrass = blockLookup.isGrassBlock(blockName);
        boolean topHeightDifferent = (height != topHeight);

        int params = 0;
        if (!isGrass) {
            params |= 1;
        }
        if (hasOverlays) {
            params |= 2;
        }
        params |= light << 8;
        params |= encodeHeightToParams(height);
        params |= encodePaletteFlags(!isGrass, blockPalette, paletteKey, biomeName, biomePalette);
        if (topHeightDifferent) {
            params |= FLAG_TOP_HEIGHT_DIFF;
        }

        dos.writeInt(params);

        if (!isGrass) {
            writeBlockStateRef(dos, blockState, paletteKey, blockPalette);
        }

        if (topHeightDifferent) {
            dos.writeByte(topHeight & 0xFF);
        }

        if (hasOverlays) {
            dos.writeByte(overlays.size());
            for (OverlayEntry overlay : overlays) {
                serializeOverlay(overlay, dos, blockPalette, blockLookup);
            }
        }

        writeBiomeRef(dos, biomeName, biomePalette);
    }

    private static void writeBlockStateRef(
            DataOutputStream dos, BlockState blockState, Map<PaletteKey, Integer> blockPalette) throws IOException {
        writeBlockStateRef(dos, blockState, PaletteKey.from(blockState), blockPalette);
    }

    private static void writeBlockStateRef(
            DataOutputStream dos, BlockState blockState, PaletteKey paletteKey, Map<PaletteKey, Integer> blockPalette)
            throws IOException {
        if (blockPalette.containsKey(paletteKey)) {
            dos.writeInt(blockPalette.get(paletteKey));
        } else {
            writeBlockState(blockState, dos);
            blockPalette.put(paletteKey, blockPalette.size());
        }
    }

    private static int encodeHeightToParams(int height) {
        return (height & 0xFF) << 12 | ((height >> 8) & 0xF) << 25;
    }

    private static void serializeOverlay(
            OverlayEntry overlay,
            DataOutputStream dos,
            Map<PaletteKey, Integer> blockPalette,
            BlockPropertyLookup blockLookup)
            throws IOException {
        BlockState blockState = overlay.blockState;
        boolean isWater = blockLookup.isWater(blockState.name());
        PaletteKey paletteKey = PaletteKey.from(blockState);

        int overlayParams = 0;
        if (!isWater) {
            overlayParams |= 1;
        }
        overlayParams |= overlay.light << 4;
        overlayParams |= overlay.opacity << 11;
        if (!isWater && !blockPalette.containsKey(paletteKey)) {
            overlayParams |= 0x400;
        }

        dos.writeInt(overlayParams);

        if (!isWater) {
            writeBlockStateRef(dos, blockState, paletteKey, blockPalette);
        }
    }
}
