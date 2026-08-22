package com.mapsyncer.mca;

import com.mapsyncer.mca.convert.biome.BiomeQuartGrid;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChunkDataParser {

    private static final Set<String> ACCEPTABLE_STATUSES = Set.of(
        "minecraft:features",
        "minecraft:light",
        "minecraft:spawn",
        "minecraft:heightmaps",
        "minecraft:full",

        "features",
        "light",
        "spawn",
        "heightmaps",
        "full"
    );

    private static boolean shouldSkipChunk(String status) {
        if (status == null || status.isEmpty()) {
            return true;
        }

        String normalizedStatus = status.contains(":") ? status : "minecraft:" + status;

        return !ACCEPTABLE_STATUSES.contains(normalizedStatus);
    }

    public record ChunkInfo(
        int chunkX,
        int chunkZ,
        int yPos,
        int chunkBottomY,
        String status,
        int[][] heightmap,
        List<ChunkSectionParser.SectionData> sections,
        int minSectionY,
        ChunkSectionParser.SectionData[] sectionLookup,
        BiomeQuartGrid biomeGrid
    ) {}

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
        if (rootType != NbtStream.TAG_COMPOUND) {
            throw new IOException("NBT文档必须以Compound开头，实际类型: " + rootType);
        }
        stream.readString();

        String status = null;
        int yPos = 0;
        List<ChunkSectionParser.SectionData> sections = new ArrayList<>();
        HeightmapFields heightmaps = new HeightmapFields();

        byte type;
        while ((type = stream.readTagType()) != NbtStream.TAG_END) {
            String key = stream.readString();
            switch (key) {
                case "Status":
                    if (type == NbtStream.TAG_STRING) {
                        status = stream.readString();
                    } else {
                        stream.skip(type);
                    }
                    break;
                case "yPos":
                    if (type == NbtStream.TAG_INT) {
                        yPos = stream.readInt();
                    } else {
                        stream.skip(type);
                    }
                    break;
                case "sections":
                    if (type == NbtStream.TAG_LIST) {
                        readSections(stream, sections);
                    } else {
                        stream.skip(type);
                    }
                    break;
                case "Heightmaps":
                    if (type == NbtStream.TAG_COMPOUND) {
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

        int chunkBottomY = yPos * 16;

        int[][] heightmap = parseHeightmap(heightmaps, chunkBottomY, worldHeightRange);

        sections.sort((a, b) -> Integer.compare(b.sectionY(), a.sectionY()));

        int maxY = sections.get(0).sectionY();
        int minSectionY = sections.get(sections.size() - 1).sectionY();
        int range = maxY - minSectionY + 1;
        ChunkSectionParser.SectionData[] sectionLookup = new ChunkSectionParser.SectionData[range];
        for (ChunkSectionParser.SectionData sec : sections) {
            int idx = sec.sectionY() - minSectionY;
            if (idx >= 0 && idx < range) sectionLookup[idx] = sec;
        }

        BiomeQuartGrid biomeGrid = BiomeQuartGrid.build(sections, minSectionY, sectionLookup);

        return new ChunkInfo(localX, localZ, yPos, chunkBottomY, status, heightmap, sections, minSectionY, sectionLookup, biomeGrid);
    }

    private static void readSections(NbtStream stream, List<ChunkSectionParser.SectionData> sections)
            throws IOException {
        byte elementType = stream.readListElementType();
        int length = stream.readListLength();
        if (elementType != NbtStream.TAG_COMPOUND) {
            for (int i = 0; i < length; i++) {
                stream.skip(elementType);
            }
            return;
        }
        for (int i = 0; i < length; i++) {
            sections.add(ChunkSectionParser.parseSection(stream));
        }
    }

    private static void readHeightmapCompound(NbtStream stream, HeightmapFields heightmaps) throws IOException {
        byte type;
        while ((type = stream.readTagType()) != NbtStream.TAG_END) {
            String key = stream.readString();
            switch (key) {
                case "WORLD_SURFACE":
                    if (type == NbtStream.TAG_LONG_ARRAY) {
                        heightmaps.worldSurface = stream.readLongArray();
                    } else {
                        stream.skip(type);
                    }
                    break;
                case "MOTION_BLOCKING_NO_LEAVES":
                    if (type == NbtStream.TAG_LONG_ARRAY) {
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
        int[][] heightmap = new int[16][16];

        if (heightmaps.worldSurface != null) {
            int bitsPerHeight = calculateBitsPerHeight(heightmaps.worldSurface.length, worldHeightRange);
            if (bitsPerHeight > 0 && bitsPerHeight <= 10) {
                decodeHeightmapLongArray(heightmaps.worldSurface, bitsPerHeight, chunkBottomY, heightmap);
                return heightmap;
            }
        }

        if (heightmaps.motionBlocking != null) {
            int bitsPerHeight = calculateBitsPerHeight(heightmaps.motionBlocking.length, worldHeightRange);
            if (bitsPerHeight > 0 && bitsPerHeight <= 10) {
                decodeHeightmapLongArray(heightmaps.motionBlocking, bitsPerHeight, chunkBottomY, heightmap);
                return heightmap;
            }
        }

        return heightmap;
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

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {

                int i = x + 16 * z;

                int longIndex = i / u;
                int bitOffset = (i % u) * bitsPerHeight;

                if (longIndex >= data.length) {
                    heightmap[x][z] = chunkBottomY;
                    continue;
                }

                long rawValue = (data[longIndex] >>> bitOffset) & ((1L << bitsPerHeight) - 1L);

                heightmap[x][z] = chunkBottomY + (int) rawValue;
            }
        }
    }

    public static ChunkSectionParser.BlockState getBlockStateAt(ChunkInfo chunk, int x, int worldY, int z) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;
        ChunkSectionParser.SectionData[] lookup = chunk.sectionLookup();
        if (lookup != null) {
            int idx = sectionY - chunk.minSectionY();
            if (idx >= 0 && idx < lookup.length && lookup[idx] != null) {
                return ChunkSectionParser.getBlockStateAt(lookup[idx], x, localY, z);
            }
        }
        return new ChunkSectionParser.BlockState("minecraft:air", Map.of());
    }

    public static String getBiomeAt(ChunkInfo chunk, int x, int worldY, int z) {
        return getBiomeAt(chunk, x, worldY, z, false);
    }

    public static String getBiomeAt(ChunkInfo chunk, int x, int worldY, int z, boolean smoothBoundary) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;
        ChunkSectionParser.SectionData[] lookup = chunk.sectionLookup();
        if (lookup != null) {
            int idx = sectionY - chunk.minSectionY();
            if (idx >= 0 && idx < lookup.length && lookup[idx] != null) {
                return ChunkSectionParser.getBiomeAt(lookup[idx], x, localY, z, smoothBoundary);
            }
        }
        return null;
    }

    public static byte getBlockLightAt(ChunkInfo chunk, int x, int worldY, int z) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;

        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.sectionY() == sectionY) {
                return ChunkSectionParser.getBlockLight(section, x, localY, z);
            }
        }

        return 0;
    }

    public static byte getSkyLightAt(ChunkInfo chunk, int x, int worldY, int z) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;

        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.sectionY() == sectionY) {
                return ChunkSectionParser.getSkyLight(section, x, localY, z);
            }
        }

        return 0;
    }

    public static int getHeightmapStartY(ChunkInfo chunk, int x, int z, int worldTopY) {
        int heightMapValue = chunk.heightmap()[x][z];

        int startY = heightMapValue + 3;

        return Math.min(startY, worldTopY - 1);
    }
}
