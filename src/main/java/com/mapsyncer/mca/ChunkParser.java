package com.mapsyncer.mca;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ChunkParser {

    private static final Set<String> ACCEPTABLE_STATUSES = Set.of(
            "minecraft:features",
            "minecraft:light",
            "minecraft:spawn",
            "minecraft:heightmaps",
            "minecraft:full");

    public record BlockState(String name, Map<String, String> properties) {

        public static final Map<String, String> EMPTY_PROPERTIES = Map.of();

        public boolean isAir() {
            return name.equals(Constants.BLOCK_AIR)
                    || name.equals(Constants.BLOCK_CAVE_AIR)
                    || name.equals(Constants.BLOCK_VOID_AIR);
        }

        public boolean isWater() {
            return name.equals(Constants.BLOCK_WATER) || name.equals(Constants.BLOCK_FLOWING_WATER);
        }

        public boolean isLava() {
            return name.equals(Constants.BLOCK_LAVA) || name.equals(Constants.BLOCK_FLOWING_LAVA);
        }

        public boolean isFluid() {
            return isWater() || isLava();
        }

        public boolean isWaterlogged() {
            return properties.containsKey(Constants.NBT_KEY_WATERLOGGED)
                    && "true".equals(properties.get(Constants.NBT_KEY_WATERLOGGED));
        }
    }

    public record SectionData(
            int sectionY,
            List<BlockState> blockPalette,
            long[] blockData,
            int blockBitsPerEntry,
            List<String> biomePalette,
            long[] biomeData,
            int biomeBitsPerEntry,
            byte[] blockLight,
            byte[] skyLight,
            int blockUVal,
            long blockMask) {}

    private record BlockData(List<BlockState> palette, long[] data) {}

    private record BiomeData(List<String> palette, long[] data) {}

    public record ChunkInfo(
            int chunkX,
            int chunkZ,
            int chunkBottomY,
            int[][] heightmap,
            List<SectionData> sections,
            int minSectionY,
            SectionData[] sectionLookup,
            BiomeResolver.BiomeQuartGrid biomeGrid) {}

    private static final class HeightmapFields {

        long[] worldSurface;

        long[] motionBlocking;
    }

    public static ChunkInfo parseChunk(int localX, int localZ, byte[] nbtData, int worldHeightRange)
            throws IOException {
        try (NbtStream stream = new NbtStream(new ByteArrayInputStream(nbtData))) {
            return parseChunk(localX, localZ, stream, worldHeightRange);
        }
    }

    private static ChunkInfo parseChunk(int localX, int localZ, NbtStream stream, int worldHeightRange)
            throws IOException {
        byte rootType = stream.readTagType();
        if (rootType != Constants.TAG_COMPOUND) {
            throw new IOException("NBT document must start with Compound, actual type: " + rootType);
        }
        stream.readString();

        String status = null;
        int yPos = 0;
        List<SectionData> sections = new ArrayList<>();
        HeightmapFields heightmaps = new HeightmapFields();

        byte type;
        while ((type = stream.readTagType()) != Constants.TAG_END) {
            String key = stream.readString();
            switch (key) {
                case Constants.NBT_KEY_STATUS:
                    if (type == Constants.TAG_STRING) {
                        status = stream.readString();
                    } else {
                        stream.skip(type);
                    }
                    break;
                case Constants.NBT_KEY_Y_POS:
                    if (type == Constants.TAG_INT) {
                        yPos = stream.readInt();
                    } else {
                        stream.skip(type);
                    }
                    break;
                case Constants.NBT_KEY_SECTIONS:
                    if (type == Constants.TAG_LIST) {
                        readSections(stream, sections);
                    } else {
                        stream.skip(type);
                    }
                    break;
                case Constants.NBT_KEY_HEIGHTMAPS:
                    if (type == Constants.TAG_COMPOUND) {
                        readHeightmapCompound(stream, heightmaps);
                    } else {
                        stream.skip(type);
                    }
                    break;
                default:
                    stream.skip(type);
            }
        }

        if (shouldSkipChunk(status)) {
            return null;
        }

        if (sections.isEmpty()) {
            return null;
        }

        int chunkBottomY = yPos * Constants.CHUNK_SIZE;

        int[][] heightmap = parseHeightmap(heightmaps, chunkBottomY, worldHeightRange);

        sections.sort((a, b) -> Integer.compare(b.sectionY(), a.sectionY()));

        int maxY = sections.get(0).sectionY();
        int minSectionY = sections.get(sections.size() - 1).sectionY();
        int range = maxY - minSectionY + 1;
        SectionData[] sectionLookup = new SectionData[range];
        for (SectionData sec : sections) {
            int idx = sec.sectionY() - minSectionY;
            if (idx >= 0 && idx < range) sectionLookup[idx] = sec;
        }

        BiomeResolver.BiomeQuartGrid biomeGrid =
                BiomeResolver.BiomeQuartGrid.build(sections, minSectionY, sectionLookup);

        return new ChunkInfo(localX, localZ, chunkBottomY, heightmap, sections, minSectionY, sectionLookup, biomeGrid);
    }

    private static boolean shouldSkipChunk(String status) {
        if (status == null || status.isEmpty()) {
            return true;
        }

        String normalizedStatus = status.contains(":") ? status : "minecraft:" + status;

        return !ACCEPTABLE_STATUSES.contains(normalizedStatus);
    }

    private static void readSections(NbtStream stream, List<SectionData> sections) throws IOException {
        byte elementType = stream.readListElementType();
        int length = stream.readListLength();
        if (elementType != Constants.TAG_COMPOUND) {
            for (int i = 0; i < length; i++) {
                stream.skip(elementType);
            }
            return;
        }
        for (int i = 0; i < length; i++) {
            sections.add(parseSection(stream));
        }
    }

    private static void readHeightmapCompound(NbtStream stream, HeightmapFields heightmaps) throws IOException {
        byte type;
        while ((type = stream.readTagType()) != Constants.TAG_END) {
            String key = stream.readString();
            switch (key) {
                case Constants.NBT_KEY_WORLD_SURFACE:
                    if (type == Constants.TAG_LONG_ARRAY) {
                        heightmaps.worldSurface = stream.readLongArray();
                    } else {
                        stream.skip(type);
                    }
                    break;
                case Constants.NBT_KEY_MOTION_BLOCKING_NO_LEAVES:
                    if (type == Constants.TAG_LONG_ARRAY) {
                        heightmaps.motionBlocking = stream.readLongArray();
                    } else {
                        stream.skip(type);
                    }
                    break;
                default:
                    stream.skip(type);
            }
        }
    }

    private static int[][] parseHeightmap(HeightmapFields heightmaps, int chunkBottomY, int worldHeightRange) {
        int[][] heightmap = new int[Constants.CHUNK_SIZE][Constants.CHUNK_SIZE];

        if (tryDecodeHeightmap(heightmaps.worldSurface, worldHeightRange, chunkBottomY, heightmap)) {
            return heightmap;
        }
        tryDecodeHeightmap(heightmaps.motionBlocking, worldHeightRange, chunkBottomY, heightmap);

        return heightmap;
    }

    private static boolean tryDecodeHeightmap(long[] data, int worldHeightRange, int chunkBottomY, int[][] heightmap) {
        if (data == null) {
            return false;
        }
        int bitsPerHeight = calculateBitsPerHeight(data.length, worldHeightRange);
        if (bitsPerHeight <= 0 || bitsPerHeight > 10) {
            return false;
        }
        decodeHeightmapLongArray(data, bitsPerHeight, chunkBottomY, heightmap);
        return true;
    }

    private static int calculateBitsPerHeight(int longArrayLength, int worldHeightRange) {

        if (worldHeightRange > 0) {

            return 32 - Integer.numberOfLeadingZeros(worldHeightRange - 1);
        }

        if (longArrayLength <= 0) return 0;
        int u = (256 + longArrayLength - 1) / longArrayLength;
        return 64 / u;
    }

    private static void decodeHeightmapLongArray(long[] data, int bitsPerHeight, int chunkBottomY, int[][] heightmap) {
        if (data == null || data.length == 0 || bitsPerHeight <= 0) {
            return;
        }

        int u = 64 / bitsPerHeight;
        long mask = (1L << bitsPerHeight) - 1L;

        for (int z = 0; z < Constants.CHUNK_SIZE; z++) {
            for (int x = 0; x < Constants.CHUNK_SIZE; x++) {
                int i = x + Constants.CHUNK_SIZE * z;
                heightmap[x][z] = chunkBottomY + readBitsFast(data, i, u, bitsPerHeight, mask);
            }
        }
    }

    static SectionData parseSection(NbtStream stream) throws IOException {
        int sectionY = 0;
        List<BlockState> blockPalette = new ArrayList<>();
        long[] blockData = null;
        List<String> biomePalette = new ArrayList<>();
        long[] biomeData = null;
        byte[] rawBlockLight = null;
        byte[] rawSkyLight = null;

        byte type;
        while ((type = stream.readTagType()) != Constants.TAG_END) {
            String key = stream.readString();
            switch (key) {
                case Constants.NBT_KEY_SECTION_Y:
                    if (type == Constants.TAG_BYTE) {
                        sectionY = stream.readByte();
                    } else {
                        stream.skip(type);
                    }
                    break;
                case Constants.NBT_KEY_BLOCK_STATES:
                    if (type == Constants.TAG_COMPOUND) {
                        BlockData blockStates = readBlockStates(stream);
                        blockPalette = blockStates.palette;
                        blockData = blockStates.data;
                    } else {
                        stream.skip(type);
                    }
                    break;
                case Constants.NBT_KEY_BIOMES:
                    if (type == Constants.TAG_COMPOUND) {
                        BiomeData biomes = readBiomes(stream);
                        biomePalette = biomes.palette;
                        biomeData = biomes.data;
                    } else {
                        stream.skip(type);
                    }
                    break;
                case Constants.NBT_KEY_BLOCK_LIGHT:
                    if (type == Constants.TAG_BYTE_ARRAY) {
                        rawBlockLight = stream.readByteArray();
                    } else {
                        stream.skip(type);
                    }
                    break;
                case Constants.NBT_KEY_SKY_LIGHT:
                    if (type == Constants.TAG_BYTE_ARRAY) {
                        rawSkyLight = stream.readByteArray();
                    } else {
                        stream.skip(type);
                    }
                    break;
                default:
                    stream.skip(type);
            }
        }

        int blockBitsPerEntry = Math.max(4, calculateBitsPerEntry(blockPalette.size()));
        int biomeBitsPerEntry = calculateBitsPerEntry(biomePalette.size());

        byte[] decodedBlockLight = decodeLightArray(rawBlockLight);
        byte[] decodedSkyLight = decodeLightArray(rawSkyLight);

        int blockUVal = 0;
        long blockMask = 0;
        if (blockData != null && blockData.length > 0 && blockBitsPerEntry > 0) {
            blockUVal = 64 / blockBitsPerEntry;
            blockMask = (1L << blockBitsPerEntry) - 1L;
        }

        return new SectionData(
                sectionY,
                blockPalette,
                blockData,
                blockBitsPerEntry,
                biomePalette,
                biomeData,
                biomeBitsPerEntry,
                decodedBlockLight,
                decodedSkyLight,
                blockUVal,
                blockMask);
    }

    private static byte[] decodeLightArray(byte[] raw) {
        if (raw == null || raw.length != 2048) {
            return null;
        }
        byte[] decoded = new byte[4096];
        for (int i = 0; i < 2048; i++) {
            int b = raw[i] & 0xFF;
            int idx = i << 1;
            decoded[idx] = (byte) (b & 0xF);
            decoded[idx + 1] = (byte) ((b >> 4) & 0xF);
        }
        return decoded;
    }

    private static BlockData readBlockStates(NbtStream stream) throws IOException {
        List<BlockState> palette = new ArrayList<>();
        long[] data = null;

        byte type;
        while ((type = stream.readTagType()) != Constants.TAG_END) {
            String key = stream.readString();
            switch (key) {
                case Constants.NBT_KEY_PALETTE:
                    if (type == Constants.TAG_LIST) {
                        byte elementType = stream.readListElementType();
                        int length = stream.readListLength();
                        if (elementType == Constants.TAG_COMPOUND) {
                            for (int i = 0; i < length; i++) {
                                BlockState blockState = parseBlockState(stream);
                                palette.add(blockState);
                            }
                        } else {
                            for (int i = 0; i < length; i++) {
                                stream.skip(elementType);
                            }
                        }
                    } else {
                        stream.skip(type);
                    }
                    break;
                case Constants.NBT_KEY_DATA:
                    if (type == Constants.TAG_LONG_ARRAY) {
                        data = stream.readLongArray();
                    } else {
                        stream.skip(type);
                    }
                    break;
                default:
                    stream.skip(type);
            }
        }
        return new BlockData(palette, data);
    }

    private static BiomeData readBiomes(NbtStream stream) throws IOException {
        List<String> palette = new ArrayList<>();
        long[] data = null;

        byte type;
        while ((type = stream.readTagType()) != Constants.TAG_END) {
            String key = stream.readString();
            switch (key) {
                case Constants.NBT_KEY_PALETTE:
                    if (type == Constants.TAG_LIST) {
                        byte elementType = stream.readListElementType();
                        int length = stream.readListLength();
                        if (elementType == Constants.TAG_STRING) {
                            for (int i = 0; i < length; i++) {
                                palette.add(stream.readString());
                            }
                        } else {
                            for (int i = 0; i < length; i++) {
                                stream.skip(elementType);
                            }
                        }
                    } else {
                        stream.skip(type);
                    }
                    break;
                case Constants.NBT_KEY_DATA:
                    if (type == Constants.TAG_LONG_ARRAY) {
                        data = stream.readLongArray();
                    } else {
                        stream.skip(type);
                    }
                    break;
                default:
                    stream.skip(type);
            }
        }
        return new BiomeData(palette, data);
    }

    private static BlockState parseBlockState(NbtStream stream) throws IOException {
        String name = null;
        Map<String, String> properties = null;

        byte type;
        while ((type = stream.readTagType()) != Constants.TAG_END) {
            String key = stream.readString();
            switch (key) {
                case Constants.NBT_KEY_NAME:
                    if (type == Constants.TAG_STRING) {
                        name = stream.readString();
                    } else {
                        stream.skip(type);
                    }
                    break;
                case Constants.NBT_KEY_PROPERTIES:
                    if (type == Constants.TAG_COMPOUND) {
                        Map<String, String> props = new LinkedHashMap<>();
                        byte pt;
                        while ((pt = stream.readTagType()) != Constants.TAG_END) {
                            String propKey = stream.readString();
                            if (pt == Constants.TAG_STRING) {
                                props.put(propKey, stream.readString());
                            } else {
                                stream.skip(pt);
                            }
                        }
                        properties = props;
                    } else {
                        stream.skip(type);
                    }
                    break;
                default:
                    stream.skip(type);
            }
        }

        return new BlockState(
                name == null ? "" : name,
                properties == null || properties.isEmpty() ? BlockState.EMPTY_PROPERTIES : properties);
    }

    private static int calculateBitsPerEntry(int paletteSize) {
        if (paletteSize <= 1) {
            return 0;
        }

        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    public static BlockState getBlockStateAt(SectionData section, int x, int y, int z) {
        if (section.blockPalette.isEmpty()) {
            return new BlockState(Constants.BLOCK_AIR, Map.of());
        }

        if (section.blockPalette.size() == 1) {
            return section.blockPalette.get(0);
        }

        if (section.blockData == null || section.blockBitsPerEntry == 0) {
            return new BlockState(Constants.BLOCK_AIR, Map.of());
        }

        int blockIndex = (y << 8) | (z << 4) | x;

        int u = section.blockUVal();
        long mask = section.blockMask();
        int paletteIndex = readBitsFast(section.blockData, blockIndex, u, section.blockBitsPerEntry(), mask);

        if (paletteIndex < 0 || paletteIndex >= section.blockPalette.size()) {
            return new BlockState(Constants.BLOCK_AIR, Map.of());
        }

        return section.blockPalette.get(paletteIndex);
    }

    public static String getBiomeAt(SectionData section, int x, int y, int z, boolean smoothBoundary) {

        if (section.biomePalette.isEmpty()) {
            return null;
        }

        if (section.biomePalette.size() == 1) {
            return section.biomePalette.get(0);
        }

        if (section.biomeData == null || section.biomeBitsPerEntry == 0) {
            return null;
        }

        int voxelY = y >> 2;
        int voxelZ = z >> 2;
        int voxelX = x >> 2;

        if (smoothBoundary) {

            int relX = x & 3;
            int relZ = z & 3;

            if (relX >= 2 && voxelX < 3) {
                voxelX++;
            }
            if (relZ >= 2 && voxelZ < 3) {
                voxelZ++;
            }
        }

        int voxelIndex = (voxelY << 4) | (voxelZ << 2) | voxelX;

        int paletteIndex = readBitsFromArray(section.biomeData, voxelIndex, section.biomeBitsPerEntry);

        if (paletteIndex < 0 || paletteIndex >= section.biomePalette.size()) {
            return null;
        }

        return section.biomePalette.get(paletteIndex);
    }

    public static int readBitsFromArray(long[] data, int index, int bitsPerEntry) {
        if (data == null || data.length == 0 || bitsPerEntry <= 0) {
            return 0;
        }
        int u = 64 / bitsPerEntry;
        long mask = (1L << bitsPerEntry) - 1L;
        return readBitsFast(data, index, u, bitsPerEntry, mask);
    }

    public static int readBitsFast(long[] data, int index, int u, int bitsPerEntry, long mask) {
        int longIndex = index / u;
        if (longIndex >= data.length) return 0;
        int bitOffset = (index % u) * bitsPerEntry;
        return (int) ((data[longIndex] >>> bitOffset) & mask);
    }

    public static byte getBlockLight(SectionData section, int x, int y, int z) {
        return getLight(section.blockLight(), x, y, z);
    }

    public static byte getSkyLight(SectionData section, int x, int y, int z) {
        return getLight(section.skyLight(), x, y, z);
    }

    private static byte getLight(byte[] light, int x, int y, int z) {
        if (light != null && light.length == 4096) {
            return light[(y << 8) | (z << 4) | x];
        }
        return 0;
    }

    public static String getBiomeAt(ChunkInfo chunk, int x, int worldY, int z, boolean smoothBoundary) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;
        SectionData[] lookup = chunk.sectionLookup();
        if (lookup != null) {
            int idx = sectionY - chunk.minSectionY();
            if (idx >= 0 && idx < lookup.length && lookup[idx] != null) {
                return getBiomeAt(lookup[idx], x, localY, z, smoothBoundary);
            }
        }
        return null;
    }

    public static int getHeightmapStartY(ChunkInfo chunk, int x, int z, int worldTopY) {
        int heightMapValue = chunk.heightmap()[x][z];

        int startY = heightMapValue + 3;

        return Math.min(startY, worldTopY - 1);
    }
}
