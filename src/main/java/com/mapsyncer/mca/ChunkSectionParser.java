package com.mapsyncer.mca;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChunkSectionParser {

    private static final Set<String> FLOWER_NAMES = Set.of(
        "minecraft:dandelion", "minecraft:poppy",
        "minecraft:blue_orchid", "minecraft:allium",
        "minecraft:red_tulip", "minecraft:orange_tulip",
        "minecraft:white_tulip", "minecraft:pink_tulip",
        "minecraft:oxeye_daisy", "minecraft:cornflower",
        "minecraft:lily_of_the_valley", "minecraft:wither_rose",
        "minecraft:sunflower", "minecraft:rose_bush",
        "minecraft:peony", "minecraft:azure_bluet",
        "minecraft:pitcher_plant"
    );

    public record BlockState(
        String name,
        Map<String, String> properties
    ) {

        public static final Map<String, String> EMPTY_PROPERTIES = Map.of();

        public String getFullName() {
            if (properties.isEmpty()) {
                return name;
            }
            StringBuilder sb = new StringBuilder(name);
            sb.append("[");
            boolean first = true;
            for (Map.Entry<String, String> e : properties.entrySet()) {
                if (!first) sb.append(",");
                sb.append(e.getKey()).append("=").append(e.getValue());
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }

        public boolean isAir() {
            return name.equals("minecraft:air") ||
                   name.equals("minecraft:cave_air") ||
                   name.equals("minecraft:void_air");
        }

        public boolean isWater() {
            return name.equals("minecraft:water") || name.equals("minecraft:flowing_water");
        }

        public boolean isLava() {
            return name.equals("minecraft:lava") || name.equals("minecraft:flowing_lava");
        }

        public boolean isFluid() {
            return isWater() || isLava();
        }

        public boolean isInvisible() {

            if (name.equals("minecraft:torch") || name.endsWith("_torch")) return true;

            if (name.equals("minecraft:short_grass") || name.equals("minecraft:grass")) return true;

            if (isFlower()) return true;

            if (name.equals("minecraft:tall_grass") || name.equals("minecraft:large_fern")) return true;
            return false;
        }

        public boolean isFlower() {
            return FLOWER_NAMES.contains(name) ||
                   name.endsWith("_tulip") ||
                   name.contains("orchid") ||
                   name.endsWith("_pitcher_crop");
        }

        public boolean isWaterlogged() {
            return properties.containsKey("waterlogged") &&
                   "true".equals(properties.get("waterlogged"));
        }

        public boolean isWaterloggedSurface() {
            return isWaterlogged() && !isWater() && !isAir();
        }
    }

    public record SectionData(
        int sectionY,
        List<BlockState> blockPalette,
        List<String> blockNames,
        long[] blockData,
        int blockBitsPerEntry,
        List<String> biomePalette,
        long[] biomeData,
        int biomeBitsPerEntry,
        byte[] blockLight,
        byte[] skyLight,
        int blockUVal,
        long blockMask
    ) {

    }

    private record BlockData(List<BlockState> palette, List<String> names, long[] data) {}

    private record BiomeData(List<String> palette, long[] data) {}

    static SectionData parseSection(NbtStream stream) throws IOException {
        int sectionY = 0;
        List<BlockState> blockPalette = new ArrayList<>();
        List<String> blockNames = new ArrayList<>();
        long[] blockData = null;
        List<String> biomePalette = new ArrayList<>();
        long[] biomeData = null;
        byte[] rawBlockLight = null;
        byte[] rawSkyLight = null;

        byte type;
        while ((type = stream.readTagType()) != NbtStream.TAG_END) {
            String key = stream.readString();
            switch (key) {
                case "Y":
                    if (type == NbtStream.TAG_BYTE) {
                        sectionY = stream.readByte();
                    } else {
                        stream.skip(type);
                    }
                    break;
                case "block_states":
                    if (type == NbtStream.TAG_COMPOUND) {
                        BlockData blockStates = readBlockStates(stream);
                        blockPalette = blockStates.palette;
                        blockNames = blockStates.names;
                        blockData = blockStates.data;
                    } else {
                        stream.skip(type);
                    }
                    break;
                case "biomes":
                    if (type == NbtStream.TAG_COMPOUND) {
                        BiomeData biomes = readBiomes(stream);
                        biomePalette = biomes.palette;
                        biomeData = biomes.data;
                    } else {
                        stream.skip(type);
                    }
                    break;
                case "BlockLight":
                    if (type == NbtStream.TAG_BYTE_ARRAY) {
                        rawBlockLight = stream.readByteArray();
                    } else {
                        stream.skip(type);
                    }
                    break;
                case "SkyLight":
                    if (type == NbtStream.TAG_BYTE_ARRAY) {
                        rawSkyLight = stream.readByteArray();
                    } else {
                        stream.skip(type);
                    }
                    break;
                default:
                    stream.skip(type);
            }
        }

        int blockBitsPerEntry = calculateBitsPerEntry(blockPalette.size(), blockData);
        int biomeBitsPerEntry = calculateBiomeBitsPerEntry(biomePalette.size(), biomeData);

        byte[] decodedBlockLight = null;
        byte[] decodedSkyLight = null;
        if (rawBlockLight != null && rawBlockLight.length == 2048) {
            decodedBlockLight = new byte[4096];
            for (int i = 0; i < 2048; i++) {
                int b = rawBlockLight[i] & 0xFF;
                int idx = i << 1;
                decodedBlockLight[idx] = (byte) (b & 0xF);
                decodedBlockLight[idx + 1] = (byte) ((b >> 4) & 0xF);
            }
        }
        if (rawSkyLight != null && rawSkyLight.length == 2048) {
            decodedSkyLight = new byte[4096];
            for (int i = 0; i < 2048; i++) {
                int b = rawSkyLight[i] & 0xFF;
                int idx = i << 1;
                decodedSkyLight[idx] = (byte) (b & 0xF);
                decodedSkyLight[idx + 1] = (byte) ((b >> 4) & 0xF);
            }
        }

        int blockUVal = 0;
        long blockMask = 0;
        if (blockData != null && blockData.length > 0 && blockBitsPerEntry > 0) {
            blockUVal = 64 / blockBitsPerEntry;
            blockMask = (1L << blockBitsPerEntry) - 1L;
        }

        return new SectionData(
            sectionY, blockPalette, blockNames, blockData, blockBitsPerEntry,
            biomePalette, biomeData, biomeBitsPerEntry,
            decodedBlockLight, decodedSkyLight,
            blockUVal, blockMask
        );
    }

    private static BlockData readBlockStates(NbtStream stream) throws IOException {
        List<BlockState> palette = new ArrayList<>();
        List<String> names = new ArrayList<>();
        long[] data = null;

        byte type;
        while ((type = stream.readTagType()) != NbtStream.TAG_END) {
            String key = stream.readString();
            switch (key) {
                case "palette":
                    if (type == NbtStream.TAG_LIST) {
                        byte elementType = stream.readListElementType();
                        int length = stream.readListLength();
                        if (elementType == NbtStream.TAG_COMPOUND) {
                            for (int i = 0; i < length; i++) {
                                BlockState blockState = parseBlockState(stream);
                                palette.add(blockState);
                                names.add(blockState.name());
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
                case "data":
                    if (type == NbtStream.TAG_LONG_ARRAY) {
                        data = stream.readLongArray();
                    } else {
                        stream.skip(type);
                    }
                    break;
                default:
                    stream.skip(type);
            }
        }
        return new BlockData(palette, names, data);
    }

    private static BiomeData readBiomes(NbtStream stream) throws IOException {
        List<String> palette = new ArrayList<>();
        long[] data = null;

        byte type;
        while ((type = stream.readTagType()) != NbtStream.TAG_END) {
            String key = stream.readString();
            switch (key) {
                case "palette":
                    if (type == NbtStream.TAG_LIST) {
                        byte elementType = stream.readListElementType();
                        int length = stream.readListLength();
                        if (elementType == NbtStream.TAG_STRING) {
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
                case "data":
                    if (type == NbtStream.TAG_LONG_ARRAY) {
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
        while ((type = stream.readTagType()) != NbtStream.TAG_END) {
            String key = stream.readString();
            switch (key) {
                case "Name":
                    if (type == NbtStream.TAG_STRING) {
                        name = stream.readString();
                    } else {
                        stream.skip(type);
                    }
                    break;
                case "Properties":
                    if (type == NbtStream.TAG_COMPOUND) {
                        Map<String, String> props = new LinkedHashMap<>();
                        byte pt;
                        while ((pt = stream.readTagType()) != NbtStream.TAG_END) {
                            String propKey = stream.readString();
                            if (pt == NbtStream.TAG_STRING) {
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
            properties == null || properties.isEmpty() ? BlockState.EMPTY_PROPERTIES : properties
        );
    }

    private static int calculateBitsPerEntry(int paletteSize, long[] data) {
        if (paletteSize <= 1) {
            return 0;
        }

        if (paletteSize <= 16) {
            return 4;
        }

        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    private static int calculateBiomeBitsPerEntry(int paletteSize, long[] data) {
        if (paletteSize <= 1) {
            return 0;
        }

        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    public static BlockState getBlockStateAt(SectionData section, int x, int y, int z) {
        if (section.blockPalette.isEmpty()) {
            return new BlockState("minecraft:air", Map.of());
        }

        if (section.blockPalette.size() == 1) {
            return section.blockPalette.get(0);
        }

        if (section.blockData == null || section.blockBitsPerEntry == 0) {
            return new BlockState("minecraft:air", Map.of());
        }

        int blockIndex = (y << 8) | (z << 4) | x;

        int u = section.blockUVal();
        long mask = section.blockMask();
        int paletteIndex = readBitsFast(section.blockData, blockIndex, u, section.blockBitsPerEntry(), mask);

        if (paletteIndex < 0 || paletteIndex >= section.blockPalette.size()) {
            return new BlockState("minecraft:air", Map.of());
        }

        return section.blockPalette.get(paletteIndex);
    }

    public static String getBlockAt(SectionData section, int x, int y, int z) {
        return getBlockStateAt(section, x, y, z).name();
    }

    public static String getBiomeAt(SectionData section, int x, int y, int z) {
        return getBiomeAt(section, x, y, z, false);
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
        byte[] d = section.blockLight();
        if (d != null && d.length == 4096) return d[(y << 8) | (z << 4) | x];
        return 0;
    }

    public static byte getSkyLight(SectionData section, int x, int y, int z) {
        byte[] d = section.skyLight();
        if (d != null && d.length == 4096) return d[(y << 8) | (z << 4) | x];
        return 0;
    }

    public static byte getLightValue(byte[] lightArray, int x, int y, int z) {
        if (lightArray == null || lightArray.length != 2048) {
            return 0;
        }

        int yzx = (y << 8) | (z << 4) | x;

        return (byte) ((lightArray[yzx >> 1] >> (4 * (yzx & 1))) & 0xF);
    }
}
