package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BlockColorMapper {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockColorMapper.class);

    private static final ConcurrentHashMap<String, Integer> blockColorCache = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Integer> textureColorCache = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Boolean> buggedBlocks = new ConcurrentHashMap<>();

    private static volatile boolean clearCachedColors = false;

    private static final Map<String, Integer> patternColors = new HashMap<>();

    static {
        initPatternColors();
    }

    private static void initPatternColors() {

        patternColors.put("_ore", 0xFDF546);
        patternColors.put("_deepslate_ore", 0xFDF546);

        patternColors.put("_log", 0x6B5231);
        patternColors.put("_wood", 0x6B5231);
        patternColors.put("_stem", 0x6B5231);
        patternColors.put("_hyphae", 0x6B5231);

        patternColors.put("_leaves", 0x3A7D23);

        patternColors.put("_planks", 0xBC945A);

        patternColors.put("stone", 0x808080);
        patternColors.put("_stone", 0x808080);
        patternColors.put("cobblestone", 0x7F7F7F);
        patternColors.put("_cobblestone", 0x7F7F7F);
        patternColors.put("deepslate", 0x6B6B6B);
        patternColors.put("_deepslate", 0x6B6B6B);

        patternColors.put("dirt", 0x866043);
        patternColors.put("_dirt", 0x866043);
        patternColors.put("grass_block", 0x5B8731);
        patternColors.put("farmland", 0x866043);
        patternColors.put("podzol", 0x6B5231);
        patternColors.put("mycelium", 0x6B5231);

        patternColors.put("sand", 0xD9E090);
        patternColors.put("_sand", 0xD9E090);
        patternColors.put("sandstone", 0xD7D2A0);
        patternColors.put("_sandstone", 0xD7D2A0);
        patternColors.put("gravel", 0x848484);

        patternColors.put("water", 0x3344FF);
        patternColors.put("_water", 0x3344FF);

        patternColors.put("lava", 0xFF6600);
        patternColors.put("_lava", 0xFF6600);

        patternColors.put("netherrack", 0x723131);
        patternColors.put("_netherrack", 0x723131);
        patternColors.put("nether_bricks", 0x2A1515);
        patternColors.put("_nether_bricks", 0x2A1515);
        patternColors.put("soul_sand", 0x50433B);
        patternColors.put("soul_soil", 0x50433B);
        patternColors.put("crimson_", 0x8B3030);
        patternColors.put("warped_", 0x2E7B5E);

        patternColors.put("end_stone", 0xD6D69D);
        patternColors.put("_end_stone", 0xD6D69D);

        patternColors.put("ice", 0xA0D0FF);
        patternColors.put("_ice", 0xA0D0FF);
        patternColors.put("snow", 0xFAFAFF);
        patternColors.put("_snow", 0xFAFAFF);

        patternColors.put("glass", 0xE0F0FF);
        patternColors.put("_glass", 0xE0F0FF);

        patternColors.put("iron", 0xD8AF8A);
        patternColors.put("_iron", 0xD8AF8A);
        patternColors.put("gold", 0xFDF546);
        patternColors.put("_gold", 0xFDF546);
        patternColors.put("copper", 0xB87333);
        patternColors.put("_copper", 0xB87333);
        patternColors.put("diamond", 0x4AEDD0);
        patternColors.put("_diamond", 0x4AEDD0);
        patternColors.put("emerald", 0x33FF66);
        patternColors.put("_emerald", 0x33FF66);
        patternColors.put("lapis", 0x3355FF);
        patternColors.put("_lapis", 0x3355FF);
        patternColors.put("redstone", 0xFF3333);
        patternColors.put("_redstone", 0xFF3333);
        patternColors.put("netherite", 0x4A4A4A);
        patternColors.put("_netherite", 0x4A4A4A);

        patternColors.put("grass", 0x7ABD47);
        patternColors.put("fern", 0x5B8731);
        patternColors.put("seagrass", 0x5B8731);
        patternColors.put("kelp", 0x5B8731);
        patternColors.put("cactus", 0x5B8731);

        patternColors.put("flower", 0xFF69B4);
        patternColors.put("rose", 0xFF3333);
        patternColors.put("tulip", 0xFF9999);
        patternColors.put("dandelion", 0xFFFF00);
        patternColors.put("orchid", 0x3399FF);

        patternColors.put("sunflower_upper", 0xFFD700);
        patternColors.put("rose_bush_upper", 0xFF3333);
        patternColors.put("peony_upper", 0xFFB6C1);
        patternColors.put("pitcher_plant_upper", 0x9932CC);

        patternColors.put("wool", 0xFFFFFF);
        patternColors.put("_wool", 0xFFFFFF);

        patternColors.put("terracotta", 0xC9674B);
        patternColors.put("_terracotta", 0xC9674B);

        patternColors.put("concrete", 0x808080);
        patternColors.put("_concrete", 0x808080);

        patternColors.put("glowstone", 0xFFCC66);
        patternColors.put("shroomlight", 0xFFCC66);
        patternColors.put("lantern", 0xFFCC66);
        patternColors.put("lamp", 0xFFCC66);
        patternColors.put("sea_lantern", 0xE0E8FF);

        patternColors.put("bricks", 0xB54B3D);
        patternColors.put("_bricks", 0xB54B3D);
        patternColors.put("brick", 0xB54B3D);

        patternColors.put("bedrock", 0x333333);
        patternColors.put("obsidian", 0x1A1A2E);
        patternColors.put("_obsidian", 0x1A1A2E);
        patternColors.put("crying_obsidian", 0x1A1A2E);
    }

    public static void clearCache() {
        clearCachedColors = true;
        blockColorCache.clear();
        textureColorCache.clear();
        buggedBlocks.clear();
    }
}