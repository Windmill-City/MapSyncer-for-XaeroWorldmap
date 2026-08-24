package com.mapsyncer.mca;

public final class Constants {

    public static final byte TAG_END = 0;
    public static final byte TAG_BYTE = 1;
    public static final byte TAG_SHORT = 2;
    public static final byte TAG_INT = 3;
    public static final byte TAG_LONG = 4;
    public static final byte TAG_FLOAT = 5;
    public static final byte TAG_DOUBLE = 6;
    public static final byte TAG_BYTE_ARRAY = 7;
    public static final byte TAG_STRING = 8;
    public static final byte TAG_LIST = 9;
    public static final byte TAG_COMPOUND = 10;
    public static final byte TAG_INT_ARRAY = 11;
    public static final byte TAG_LONG_ARRAY = 12;

    public static final String NBT_KEY_STATUS = "Status";
    public static final String NBT_KEY_Y_POS = "yPos";
    public static final String NBT_KEY_SECTIONS = "sections";
    public static final String NBT_KEY_HEIGHTMAPS = "Heightmaps";
    public static final String NBT_KEY_SECTION_Y = "Y";
    public static final String NBT_KEY_BLOCK_STATES = "block_states";
    public static final String NBT_KEY_BIOMES = "biomes";
    public static final String NBT_KEY_PALETTE = "palette";
    public static final String NBT_KEY_DATA = "data";
    public static final String NBT_KEY_BLOCK_LIGHT = "BlockLight";
    public static final String NBT_KEY_SKY_LIGHT = "SkyLight";
    public static final String NBT_KEY_WORLD_SURFACE = "WORLD_SURFACE";
    public static final String NBT_KEY_MOTION_BLOCKING_NO_LEAVES = "MOTION_BLOCKING_NO_LEAVES";
    public static final String NBT_KEY_NAME = "Name";
    public static final String NBT_KEY_PROPERTIES = "Properties";
    public static final String NBT_KEY_WATERLOGGED = "waterlogged";

    public static final String BLOCK_AIR = "minecraft:air";
    public static final String BLOCK_CAVE_AIR = "minecraft:cave_air";
    public static final String BLOCK_VOID_AIR = "minecraft:void_air";
    public static final String BLOCK_WATER = "minecraft:water";
    public static final String BLOCK_FLOWING_WATER = "minecraft:flowing_water";
    public static final String BLOCK_LAVA = "minecraft:lava";
    public static final String BLOCK_FLOWING_LAVA = "minecraft:flowing_lava";

    public static final String BIOME_THE_VOID = "minecraft:the_void";

    public static final String DIM_OVERWORLD = "minecraft:overworld";
    public static final String DIM_THE_NETHER = "minecraft:the_nether";
    public static final String DIM_THE_END = "minecraft:the_end";

    public static final int CHUNK_SIZE = 16;
    public static final int CHUNKS_PER_REGION = 32;
    public static final int REGION_SIZE_BLOCKS = CHUNKS_PER_REGION * CHUNK_SIZE;
    public static final int SECTOR_SIZE = 4096;
    public static final int MAX_LIGHT_LEVEL = 15;
    public static final int CAVE_DEPTH = 15;
}
