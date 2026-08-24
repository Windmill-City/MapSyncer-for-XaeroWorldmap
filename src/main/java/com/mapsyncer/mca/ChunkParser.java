package com.mapsyncer.mca;

import com.mojang.serialization.Codec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

public final class ChunkParser {

    private static final Set<String> ACCEPTABLE_STATUSES = Set.of(
            "minecraft:features", "minecraft:light", "minecraft:spawn", "minecraft:heightmaps", "minecraft:full");

    private static final Codec<PalettedContainer<net.minecraft.world.level.block.state.BlockState>> BLOCK_STATE_CODEC =
            PalettedContainer.codecRW(
                    Block.BLOCK_STATE_REGISTRY,
                    net.minecraft.world.level.block.state.BlockState.CODEC,
                    PalettedContainer.Strategy.SECTION_STATES,
                    Blocks.AIR.defaultBlockState());

    private static final Map<net.minecraft.world.level.block.state.BlockState, BlockState> VANILLA_STATE_CACHE =
            new ConcurrentHashMap<>();

    public record BlockState(String name, Map<String, String> properties) {

        public static final Map<String, String> EMPTY_PROPERTIES = Map.of();

        public static final BlockState AIR = new BlockState(Constants.BLOCK_AIR, EMPTY_PROPERTIES);

        static BlockState fromVanilla(net.minecraft.world.level.block.state.BlockState state) {
            return VANILLA_STATE_CACHE.computeIfAbsent(state, ChunkParser::convertVanilla);
        }

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
            int sectionY, LevelChunkSection section, DataLayer blockLight, DataLayer skyLight, boolean hasBiomes) {

        public boolean hasBlocks() {
            return section != null;
        }

        public BlockState getBlockState(int x, int y, int z) {
            if (section == null) {
                return BlockState.AIR;
            }
            return BlockState.fromVanilla(section.getBlockState(x, y, z));
        }

        public byte getBlockLight(int x, int y, int z) {
            return blockLight == null ? 0 : (byte) blockLight.get(x, y, z);
        }

        public byte getSkyLight(int x, int y, int z) {
            return skyLight == null ? 0 : (byte) skyLight.get(x, y, z);
        }

        public String getBiomeAt(int x, int y, int z, boolean smoothBoundary) {
            if (section == null) {
                return null;
            }
            int voxelX = x >> 2;
            int voxelY = y >> 2;
            int voxelZ = z >> 2;
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
            return biomeName(section.getNoiseBiome(voxelX, voxelY, voxelZ));
        }
    }

    public record ChunkInfo(
            int chunkX,
            int chunkZ,
            int chunkBottomY,
            int[][] heightmap,
            List<SectionData> sections,
            int minSectionY,
            SectionData[] sectionLookup,
            BiomeResolver.BiomeQuartGrid biomeGrid) {}

    public static ChunkInfo parseChunk(
            int localX, int localZ, byte[] nbtData, int worldHeightRange, Registry<Biome> biomeRegistry)
            throws IOException {
        CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbtData)));
        return parseChunk(localX, localZ, tag, worldHeightRange, biomeRegistry);
    }

    private static ChunkInfo parseChunk(
            int localX, int localZ, CompoundTag tag, int worldHeightRange, Registry<Biome> biomeRegistry) {
        if (!ACCEPTABLE_STATUSES.contains(tag.getString(Constants.NBT_KEY_STATUS))) {
            return null;
        }

        int chunkBottomY = tag.getInt(Constants.NBT_KEY_Y_POS) * Constants.CHUNK_SIZE;

        List<SectionData> sections = new ArrayList<>();
        ListTag sectionsTag = tag.getList(Constants.NBT_KEY_SECTIONS, Constants.TAG_COMPOUND);
        for (int i = 0; i < sectionsTag.size(); i++) {
            sections.add(parseSection(sectionsTag.getCompound(i), biomeRegistry));
        }

        if (sections.isEmpty()) {
            return null;
        }

        int[][] heightmap = parseHeightmap(tag, chunkBottomY, worldHeightRange);

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

    private static SectionData parseSection(CompoundTag tag, Registry<Biome> biomeRegistry) {
        int sectionY = tag.getByte(Constants.NBT_KEY_SECTION_Y);

        LevelChunkSection section = null;
        boolean hasBiomes = false;
        if (tag.contains(Constants.NBT_KEY_BLOCK_STATES, Constants.TAG_COMPOUND)) {
            PalettedContainer<net.minecraft.world.level.block.state.BlockState> blocks = BLOCK_STATE_CODEC
                    .parse(NbtOps.INSTANCE, tag.getCompound(Constants.NBT_KEY_BLOCK_STATES))
                    .getOrThrow(false, e -> {});
            PalettedContainerRO<Holder<Biome>> biomes;
            if (tag.contains(Constants.NBT_KEY_BIOMES, Constants.TAG_COMPOUND)) {
                biomes = biomeCodec(biomeRegistry)
                        .parse(NbtOps.INSTANCE, tag.getCompound(Constants.NBT_KEY_BIOMES))
                        .getOrThrow(false, e -> {});
                hasBiomes = true;
            } else {
                biomes = new PalettedContainer<>(
                        biomeRegistry.asHolderIdMap(),
                        biomeRegistry.getHolderOrThrow(Biomes.PLAINS),
                        PalettedContainer.Strategy.SECTION_BIOMES);
            }
            section = new LevelChunkSection(blocks, biomes);
        }

        DataLayer blockLight = readLight(tag, Constants.NBT_KEY_BLOCK_LIGHT);
        DataLayer skyLight = readLight(tag, Constants.NBT_KEY_SKY_LIGHT);

        return new SectionData(sectionY, section, blockLight, skyLight, hasBiomes);
    }

    private static Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec(Registry<Biome> biomeRegistry) {
        return PalettedContainer.codecRO(
                biomeRegistry.asHolderIdMap(),
                biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES,
                biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
    }

    private static DataLayer readLight(CompoundTag tag, String key) {
        if (!tag.contains(key, Constants.TAG_BYTE_ARRAY)) {
            return null;
        }
        byte[] raw = tag.getByteArray(key);
        return raw.length == DataLayer.SIZE ? new DataLayer(raw) : null;
    }

    private static String biomeName(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.location().toString()).orElse(null);
    }

    private static BlockState convertVanilla(net.minecraft.world.level.block.state.BlockState state) {
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (state.getValues().isEmpty()) {
            return new BlockState(name, BlockState.EMPTY_PROPERTIES);
        }
        Map<String, String> properties = new LinkedHashMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            properties.put(entry.getKey().getName(), entry.getValue().toString());
        }
        return new BlockState(name, properties);
    }

    private static int[][] parseHeightmap(CompoundTag tag, int chunkBottomY, int worldHeightRange) {
        int[][] heightmap = new int[Constants.CHUNK_SIZE][Constants.CHUNK_SIZE];
        CompoundTag heightmapsTag = tag.getCompound(Constants.NBT_KEY_HEIGHTMAPS);
        if (tryDecodeHeightmap(
                heightmapsTag, Constants.NBT_KEY_WORLD_SURFACE, chunkBottomY, worldHeightRange, heightmap)) {
            return heightmap;
        }
        tryDecodeHeightmap(
                heightmapsTag, Constants.NBT_KEY_MOTION_BLOCKING_NO_LEAVES, chunkBottomY, worldHeightRange, heightmap);
        return heightmap;
    }

    private static boolean tryDecodeHeightmap(
            CompoundTag heightmapsTag, String key, int chunkBottomY, int worldHeightRange, int[][] heightmap) {
        if (!heightmapsTag.contains(key, Constants.TAG_LONG_ARRAY)) {
            return false;
        }
        long[] data = heightmapsTag.getLongArray(key);
        if (data.length == 0) {
            return false;
        }
        int bits = Mth.ceillog2(worldHeightRange + 1);
        if (bits < 1) {
            return false;
        }
        try {
            SimpleBitStorage storage = new SimpleBitStorage(bits, Constants.CHUNK_SIZE * Constants.CHUNK_SIZE, data);
            for (int z = 0; z < Constants.CHUNK_SIZE; z++) {
                for (int x = 0; x < Constants.CHUNK_SIZE; x++) {
                    heightmap[x][z] = chunkBottomY + storage.get(x + z * Constants.CHUNK_SIZE);
                }
            }
            return true;
        } catch (SimpleBitStorage.InitializationException e) {
            return false;
        }
    }

    public static String getBiomeAt(ChunkInfo chunk, int x, int worldY, int z, boolean smoothBoundary) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;
        SectionData[] lookup = chunk.sectionLookup();
        if (lookup != null) {
            int idx = sectionY - chunk.minSectionY();
            if (idx >= 0 && idx < lookup.length && lookup[idx] != null) {
                return lookup[idx].getBiomeAt(x, localY, z, smoothBoundary);
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
