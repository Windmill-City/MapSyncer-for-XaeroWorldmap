package com.mapsyncer.mca;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.GlassBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Replicates Xaero WorldMap's {@code WorldDataReader.buildTile} column scan for one
 * {@code .mca} chunk, producing a 16x16 grid of {@link PixelData} in [x][z] order.
 * Transparent blocks (water, glass, air) are stacked as {@link PixelData.Overlay}s above
 * the top visible block, matching the values Xaero persists in the {@code region.xaero}
 * pixel payload (parametres bitfield, top height, overlays, biome).
 */
final class ChunkBuilder {

    /**
     * One map pixel of a built chunk: the top visible block state at (x, z) plus its
     * absolute height, the height of the topmost transparent block above it and the
     * light level captured at the column.
     */
    record PixelData(
            BlockState state,
            short height,
            short topHeight,
            byte light,
            ResourceKey<Biome> biome,
            List<Overlay> overlays) {

        /**
         * One transparent block stacked above the pixel, e.g. water, with its opacity
         * (accumulated light-block value, 0-15) and the light level captured at its
         * first block, byte-compatible with Xaero's {@code Overlay}.
         */
        record Overlay(BlockState state, byte opacity, byte light) {}

        boolean hasOverlays() {
            return !overlays.isEmpty();
        }
    }

    private static final int MAX_OVERLAYS = 10;
    private static final int HEIGHTMAP_ENTRIES = 256;
    private static final Logger LOGGER = LogManager.getLogger();

    private static final List<BlockState> buggedStates = new ArrayList<>();

    private static final boolean[] underair = new boolean[HEIGHTMAP_ENTRIES];
    private static final boolean[] shouldEnterGround = new boolean[HEIGHTMAP_ENTRIES];
    private static final boolean[] blockFound = new boolean[HEIGHTMAP_ENTRIES];
    private static final boolean[] shouldExtendTillTheBottom = new boolean[HEIGHTMAP_ENTRIES];
    private static final byte[] lightLevels = new byte[HEIGHTMAP_ENTRIES];
    private static final byte[] skyLightLevels = new byte[HEIGHTMAP_ENTRIES];
    private static final int[] topH = new int[HEIGHTMAP_ENTRIES];
    private static final int[] firstTransparentStateY = new int[HEIGHTMAP_ENTRIES];
    private static final OverlayBuilder[] overlayBuilders = new OverlayBuilder[HEIGHTMAP_ENTRIES];
    private static final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private static final List<BlockState> blockStatePalette = new ArrayList<>();
    private static final Map<Integer, BiomeSection> biomeSections = new HashMap<>();
    private static final Map<String, ResourceKey<Biome>> biomeKeyCache = new HashMap<>();
    private static SimpleBitStorage heightMapBitArray;
    private static SimpleBitStorage blockStatesBitArray;

    private static ServerLevel level;
    private static int worldBottomY;
    private static int worldTopY;
    private static boolean worldHasSkylight;
    private static HolderLookup.RegistryLookup<Biome> biomeRegistry;
    private static ResourceKey<Biome> voidBiomeKey;

    static {
        for (int i = 0; i < overlayBuilders.length; i++) {
            overlayBuilders[i] = new OverlayBuilder();
        }
    }

    /**
     * Builds the pixel grid for one chunk. Returns {@code null} when the chunk is missing
     * or not generated far enough (status below {@link ChunkStatus#FEATURES}).
     */
    static PixelData[][] build(
            CompoundTag chunk,
            int chunkX,
            int chunkZ,
            int caveStart,
            int caveDepth,
            ServerLevel level,
            HolderGetter<Block> blockLookup) {
        ChunkBuilder.level = level;
        worldBottomY = level.getMinBuildHeight();
        worldTopY = level.getMaxBuildHeight();
        worldHasSkylight = level.dimensionType().hasSkyLight();
        biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        ResourceKey<Biome> voidKey =
                ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("the_void"));
        voidBiomeKey = biomeRegistry.get(voidKey).isPresent() ? voidKey : null;

        boolean cave = caveStart != Integer.MAX_VALUE;
        boolean fullCave = caveStart == Integer.MIN_VALUE;

        boolean oldOptimizedChunk = chunk.contains("below_zero_retrogen");
        String status = oldOptimizedChunk
                ? chunk.getCompound("below_zero_retrogen").getString("target_status")
                : chunk.getString("Status");
        ChunkStatus chunkStatus = ChunkStatus.byName(status);
        if (chunkStatus == null || chunkStatus.getIndex() < ChunkStatus.BIOMES.getIndex()) {
            LOGGER.debug("Chunk ({}, {}) has unusable status '{}', skipping (below BIOMES)", chunkX, chunkZ, status);
            return null;
        }
        readBiomes(chunk);
        if (chunkStatus.getIndex() < ChunkStatus.FEATURES.getIndex()) {
            LOGGER.debug("Chunk ({}, {}) has status '{}' (below FEATURES), skipping", chunkX, chunkZ, status);
            return null;
        }

        PixelData[][] pixels = new PixelData[16][16];
        int chunkBottomY = chunk.getInt("yPos") * 16;

        boolean[] blockFound = ChunkBuilder.blockFound;
        boolean[] underair = ChunkBuilder.underair;
        boolean[] shouldEnterGround = ChunkBuilder.shouldEnterGround;
        boolean[] shouldExtendTillTheBottom = ChunkBuilder.shouldExtendTillTheBottom;
        byte[] lightLevels = ChunkBuilder.lightLevels;
        byte[] skyLightLevels = ChunkBuilder.skyLightLevels;
        int[] topH = ChunkBuilder.topH;
        int[] firstTransparentStateY = ChunkBuilder.firstTransparentStateY;
        OverlayBuilder[] overlayBuilders = ChunkBuilder.overlayBuilders;
        for (int i = 0; i < HEIGHTMAP_ENTRIES; i++) {
            overlayBuilders[i].startBuilding();
            blockFound[i] = false;
            underair[i] = shouldEnterGround[i] = fullCave;
            lightLevels[i] = 0;
            skyLightLevels[i] = (byte) (worldHasSkylight ? 15 : 0);
            topH[i] = worldBottomY;
            shouldExtendTillTheBottom[i] = false;
        }

        int[] heightMapValues = null;
        boolean heightMapExists;
        if (!chunk.contains("Heightmaps", 10)) {
            heightMapValues = chunk.getIntArray("HeightMap");
            heightMapExists = heightMapValues.length == HEIGHTMAP_ENTRIES;
        } else {
            long[] heightMapArray = chunk.getCompound("Heightmaps").getLongArray("WORLD_SURFACE");
            int potentialBitsPerHeight = heightMapArray.length / 4;
            heightMapExists = potentialBitsPerHeight > 0 && potentialBitsPerHeight <= 10;
            if (heightMapExists) {
                updateHeightArray(potentialBitsPerHeight);
                System.arraycopy(heightMapArray, 0, heightMapBitArray.getRaw(), 0, heightMapArray.length);
            }
        }

        int caveStartSectionHeight = (fullCave ? worldTopY - 1 : caveStart) >> 4 << 4;
        int lowH = worldBottomY;
        if (cave && !fullCave && (lowH = caveStart + 1 - caveDepth) < worldBottomY) {
            lowH = worldBottomY;
        }
        int lowHSection = lowH >> 4 << 4;

        ListTag sectionsList = chunk.getList("sections", 10);
        int fillCounter = HEIGHTMAP_ENTRIES;
        if (sectionsList.size() == 0) {
            LOGGER.debug("Chunk ({}, {}) has no sections (Status={}), building void/air tile", chunkX, chunkZ, status);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    pixels[x][z] = airPixel();
                }
            }
        } else {
            int prevSectionHeight = Integer.MAX_VALUE;
            int sectionHeight = Integer.MAX_VALUE;
            for (int i = sectionsList.size() - 1; i >= 0 && fillCounter > 0; i--) {
                CompoundTag sectionCompound = sectionsList.getCompound(i);
                sectionHeight = sectionCompound.getByte("Y") * 16;
                boolean hasBlocks = false;
                CompoundTag blockStatesCompound = null;
                if (sectionCompound.contains("block_states", 10)) {
                    blockStatesCompound = sectionCompound.getCompound("block_states");
                    hasBlocks = sectionHeight >= lowHSection;
                    if (hasBlocks
                            && !(hasBlocks = blockStatesCompound.contains("data", 12))
                            && blockStatesCompound.contains("palette", 9)) {
                        ListTag paletteList = blockStatesCompound.getList("palette", 10);
                        hasBlocks = paletteList.size() == 1
                                && !((CompoundTag) paletteList.get(0))
                                        .get("Name")
                                        .getAsString()
                                        .equals("minecraft:air");
                    }
                }
                if (i > 0
                        && !hasBlocks
                        && !sectionCompound.contains("BlockLight", 7)
                        && (!cave || !sectionCompound.contains("SkyLight", 7))) {
                    continue;
                }
                boolean previousSectionExists = prevSectionHeight - sectionHeight == 16;
                boolean underAirByDefault = cave && !previousSectionExists && caveStartSectionHeight > sectionHeight;
                int sectionBasedHeight = sectionHeight + 15;
                boolean preparedSectionData = false;
                boolean hasDifferentBlockStates = false;
                byte[] lightMap = null;
                byte[] skyLightMap = null;
                prevSectionHeight = sectionHeight;
                for (int z = 0; z < 16; z++) {
                    columnLoop:
                    for (int x = 0; x < 16; x++) {
                        int pos2d = (z << 4) + x;
                        if (blockFound[pos2d]) {
                            continue;
                        }
                        int heightMapValue;
                        if (heightMapExists) {
                            heightMapValue = heightMapValues != null
                                    ? heightMapValues[pos2d]
                                    : chunkBottomY + heightMapBitArray.get(pos2d);
                        } else {
                            heightMapValue = Integer.MIN_VALUE;
                        }
                        int startHeight;
                        if (cave && !fullCave) {
                            startHeight = caveStart;
                        } else {
                            startHeight = heightMapValue < chunkBottomY ? sectionBasedHeight : heightMapValue + 3;
                        }
                        if (startHeight >= worldTopY) {
                            startHeight = worldTopY - 1;
                        }
                        if (i > 0 && ++startHeight < sectionHeight) {
                            continue;
                        }
                        int localStartHeight = 15;
                        if (startHeight >> 4 << 4 == sectionHeight) {
                            localStartHeight = startHeight & 0xF;
                        }
                        if (!preparedSectionData) {
                            if (hasBlocks) {
                                ListTag paletteList = blockStatesCompound.getList("palette", 10);
                                hasDifferentBlockStates =
                                        blockStatesCompound.contains("data", 12) && paletteList.size() > 1;
                                boolean shouldReadPalette = true;
                                if (hasDifferentBlockStates) {
                                    long[] blockStatesArray = blockStatesCompound.getLongArray("data");
                                    int bits = blockStatesArray.length * 64 / 4096;
                                    int bitsOther = Math.max(4, Mth.ceillog2(paletteList.size()));
                                    if (bitsOther > 8) {
                                        bits = bitsOther;
                                    }
                                    if (bits < 2) {
                                        hasDifferentBlockStates = false;
                                        shouldReadPalette = false;
                                    } else {
                                        if (blockStatesBitArray == null || blockStatesBitArray.getBits() != bits) {
                                            blockStatesBitArray = new SimpleBitStorage(bits, 4096);
                                        }
                                        if (blockStatesArray.length == blockStatesBitArray.getRaw().length) {
                                            System.arraycopy(
                                                    blockStatesArray,
                                                    0,
                                                    blockStatesBitArray.getRaw(),
                                                    0,
                                                    blockStatesArray.length);
                                        } else {
                                            hasDifferentBlockStates = false;
                                            shouldReadPalette = false;
                                        }
                                    }
                                }
                                blockStatePalette.clear();
                                if (shouldReadPalette) {
                                    for (Tag stateTag : paletteList) {
                                        blockStatePalette.add(
                                                NbtUtils.readBlockState(blockLookup, (CompoundTag) stateTag));
                                    }
                                }
                            }
                            if (sectionCompound.contains("BlockLight", 7)
                                    && (lightMap = sectionCompound.getByteArray("BlockLight")).length != 2048) {
                                lightMap = null;
                            }
                            if (cave
                                    && sectionCompound.contains("SkyLight", 7)
                                    && (skyLightMap = sectionCompound.getByteArray("SkyLight")).length != 2048) {
                                skyLightMap = null;
                            }
                            preparedSectionData = true;
                        }
                        if (underAirByDefault) {
                            underair[pos2d] = true;
                        }
                        for (int y = localStartHeight; y >= 0; y--) {
                            int h = sectionHeight | y;
                            int pos = y << 8 | pos2d;
                            BlockState state = null;
                            if (hasBlocks) {
                                int indexInPalette = hasDifferentBlockStates ? blockStatesBitArray.get(pos) : 0;
                                if (indexInPalette < blockStatePalette.size()) {
                                    state = blockStatePalette.get(indexInPalette);
                                }
                            }
                            if (state == null) {
                                state = Blocks.AIR.defaultBlockState();
                            }
                            mutablePos.set(chunkX << 4 | x, h, chunkZ << 4 | z);
                            OverlayBuilder overlayBuilder = overlayBuilders[pos2d];
                            if (!shouldExtendTillTheBottom[pos2d]
                                    && !overlayBuilder.isEmpty()
                                    && firstTransparentStateY[pos2d] - h >= 5) {
                                shouldExtendTillTheBottom[pos2d] = true;
                            }
                            boolean buildResult = h >= lowH
                                    && h < startHeight
                                    && buildPixel(state, x, h, z, pos2d, cave, fullCave, overlayBuilder);
                            if (!buildResult && (y == 0 && i == 0 || h <= lowH)) {
                                lightLevels[pos2d] = 0;
                                if (cave) {
                                    skyLightLevels[pos2d] = 0;
                                }
                                h = worldBottomY;
                                state = Blocks.AIR.defaultBlockState();
                                buildResult = true;
                            }
                            if (buildResult) {
                                byte light = lightLevels[pos2d];
                                if (cave && light < 15 && overlayBuilder.isEmpty() && skyLightLevels[pos2d] > light) {
                                    light = skyLightLevels[pos2d];
                                }
                                pixels[x][z] = new PixelData(
                                        state,
                                        (short) h,
                                        (short) topH[pos2d],
                                        light,
                                        sampleBiome(x, topH[pos2d], z),
                                        overlayBuilder.finishBuilding());
                                blockFound[pos2d] = true;
                                fillCounter--;
                                continue columnLoop;
                            }
                            byte dataLight = lightMap == null ? 0 : nibbleValue(lightMap, pos);
                            if (cave && dataLight < 15 && worldHasSkylight) {
                                int dataSkyLight = !fullCave && startHeight > heightMapValue
                                        ? 15
                                        : (skyLightMap == null ? 0 : nibbleValue(skyLightMap, pos));
                                skyLightLevels[pos2d] = (byte) dataSkyLight;
                            }
                            lightLevels[pos2d] = dataLight;
                        }
                    }
                }
            }
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (pixels[x][z] == null) {
                    pixels[x][z] = airPixel();
                }
            }
        }
        return pixels;
    }

    private static PixelData airPixel() {
        return new PixelData(
                Blocks.AIR.defaultBlockState(), (short) worldBottomY, (short) worldBottomY, (byte) 0, null, List.of());
    }

    private static boolean buildPixel(
            BlockState state,
            int x,
            int h,
            int z,
            int pos2d,
            boolean cave,
            boolean fullCave,
            OverlayBuilder overlayBuilder) {
        FluidState fluidFluidState = state.getFluidState();
        Block b = state.getBlock();
        if (!(fluidFluidState.isEmpty() || cave && shouldEnterGround[pos2d])) {
            underair[pos2d] = true;
            BlockState fluidState = fluidFluidState.createLegacyBlock();
            if (buildPixelHelp(fluidState, fluidState.getBlock(), fluidFluidState, pos2d, h, cave, overlayBuilder)) {
                return true;
            }
        }
        if (b instanceof AirBlock) {
            underair[pos2d] = true;
            return false;
        }
        if (!underair[pos2d] && cave) {
            return false;
        }
        if (b == fluidFluidState.createLegacyBlock().getBlock()) {
            return false;
        }
        if (cave && shouldEnterGround[pos2d]) {
            if (!(state.ignitedByLava()
                    || state.canBeReplaced()
                    || state.getPistonPushReaction() == PushReaction.DESTROY
                    || shouldOverlay(state))) {
                underair[pos2d] = false;
                shouldEnterGround[pos2d] = false;
            }
            return false;
        }
        return buildPixelHelp(state, b, null, pos2d, h, cave, overlayBuilder);
    }

    private static boolean buildPixelHelp(
            BlockState state,
            Block b,
            FluidState fluidFluidState,
            int pos2d,
            int h,
            boolean cave,
            OverlayBuilder overlayBuilder) {
        if (isInvisible(state, b, cave)) {
            return false;
        }
        if (shouldOverlay(fluidFluidState == null ? state : fluidFluidState)) {
            if (cave && !underair[pos2d]) {
                return false;
            }
            if (h > topH[pos2d]) {
                topH[pos2d] = h;
            }
            byte overlayLight = lightLevels[pos2d];
            if (overlayBuilder.isEmpty()) {
                firstTransparentStateY[pos2d] = h;
                if (cave && skyLightLevels[pos2d] > overlayLight) {
                    overlayLight = skyLightLevels[pos2d];
                }
            }
            if (shouldExtendTillTheBottom[pos2d]) {
                PixelData.Overlay current = overlayBuilder.current();
                if (current != null) {
                    overlayBuilder.increaseOpacity(current.state().getLightBlock(level, mutablePos));
                }
            } else {
                overlayBuilder.build(state, state.getLightBlock(level, mutablePos), overlayLight);
            }
            return false;
        }
        if (!hasVanillaColor(state)) {
            return false;
        }
        if (cave && !underair[pos2d]) {
            return true;
        }
        if (h > topH[pos2d]) {
            topH[pos2d] = h;
        }
        return true;
    }

    private static boolean isInvisible(BlockState state, Block b, boolean cave) {
        if (!(b instanceof LiquidBlock) && state.getRenderShape() == RenderShape.INVISIBLE) {
            return true;
        }
        if (b == Blocks.TORCH) {
            return true;
        }
        if (b == Blocks.GRASS) {
            return true;
        }
        if (b == Blocks.GLASS || b == Blocks.GLASS_PANE) {
            return true;
        }
        boolean notCave = b instanceof PitcherCropBlock
                || b instanceof TallFlowerBlock
                || b instanceof FlowerBlock
                || state.is(BlockTags.FLOWERS) && !state.is(BlockTags.LEAVES);
        if (b instanceof DoublePlantBlock && !notCave) {
            return true;
        }
        if (notCave && !cave) {
            return true;
        }
        synchronized (buggedStates) {
            return buggedStates.contains(state);
        }
    }

    private static boolean shouldOverlay(StateHolder<?, ?> state) {
        if (state instanceof BlockState blockState) {
            if (blockState.getBlock() instanceof AirBlock || blockState.getBlock() instanceof GlassBlock) {
                return true;
            }
            return false;
        }
        FluidState fluidState = (FluidState) state;
        return !fluidState.is(FluidTags.LAVA);
    }

    private static boolean hasVanillaColor(BlockState state) {
        MapColor color = null;
        try {
            color = state.getMapColor(level, mutablePos);
        } catch (Throwable t) {
            synchronized (buggedStates) {
                buggedStates.add(state);
            }
            LOGGER.info("Found bugged state! Adding to bugged list: "
                    + level.registryAccess().registryOrThrow(Registries.BLOCK).getKey(state.getBlock()));
        }
        return color != null && color.col != 0;
    }

    private static void updateHeightArray(int bitsPerHeight) {
        if (heightMapBitArray == null || heightMapBitArray.getBits() != bitsPerHeight) {
            heightMapBitArray = new SimpleBitStorage(bitsPerHeight, HEIGHTMAP_ENTRIES);
        }
    }

    private static byte nibbleValue(byte[] array, int index) {
        byte b = array[index >> 1];
        if ((index & 1) == 0) {
            return (byte) (b & 0xF);
        }
        return (byte) (b >> 4 & 0xF);
    }

    private static void readBiomes(CompoundTag chunk) {
        biomeSections.clear();
        biomeKeyCache.clear();
        ListTag sections = chunk.getList("sections", 10);
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            if (!section.contains("biomes", 10)) {
                continue;
            }
            CompoundTag biomes = section.getCompound("biomes");
            if (!biomes.contains("palette", 9)) {
                continue;
            }
            ListTag paletteList = biomes.getList("palette", 8);
            long[] data = null;
            if (biomes.contains("data", 12) && paletteList.size() > 1) {
                data = biomes.getLongArray("data");
            }
            biomeSections.put((int) section.getByte("Y"), new BiomeSection(paletteList, data));
        }
    }

    private static ResourceKey<Biome> sampleBiome(int x, int y, int z) {
        BiomeSection section = biomeSections.get(y >> 4);
        String biomeId = section == null ? null : section.get(x >> 2, y >> 2 & 3, z >> 2);
        if (biomeId == null) {
            return voidBiomeKey;
        }
        ResourceKey<Biome> key = biomeKeyCache.get(biomeId);
        if (key == null) {
            ResourceLocation location = ResourceLocation.tryParse(biomeId);
            if (location == null) {
                return voidBiomeKey;
            }
            key = ResourceKey.create(Registries.BIOME, location);
            if (!biomeRegistry.get(key).isPresent()) {
                key = voidBiomeKey;
            }
            biomeKeyCache.put(biomeId, key);
        }
        return key;
    }

    /** Per-section 4x4x4 biome grid, mirroring Xaero's {@code WorldDataReaderSectionBiomeData}. */
    private static final class BiomeSection {

        private final List<String> palette;

        private final long[] data;

        private SimpleBitStorage bitStorage;

        BiomeSection(ListTag paletteList, long[] data) {
            List<String> palette = new ArrayList<>(paletteList.size());
            for (Tag tag : paletteList) {
                palette.add(tag.getAsString());
            }
            this.palette = palette;
            this.data = data;
        }

        String get(int quadX, int quadY, int quadZ) {
            if (data == null) {
                return palette.isEmpty() ? null : palette.get(0);
            }
            if (bitStorage == null) {
                int bits = Mth.ceillog2(palette.size());
                try {
                    bitStorage = new SimpleBitStorage(bits, 64, data);
                } catch (RuntimeException e) {
                    return palette.isEmpty() ? null : palette.get(0);
                }
            }
            int pos3D = quadY << 4 | quadZ << 2 | quadX;
            int paletteIndex = bitStorage.get(pos3D);
            if (paletteIndex >= palette.size()) {
                return null;
            }
            return palette.get(paletteIndex);
        }
    }

    /**
     * Per-column overlay accumulator, mirroring Xaero's {@code OverlayBuilder}: consecutive
     * transparent blocks of the same state merge into one overlay whose opacity accumulates
     * (capped at 15) and whose light is captured at the first block of the run.
     */
    private static final class OverlayBuilder {

        private final List<PixelData.Overlay> overlays = new ArrayList<>(MAX_OVERLAYS);

        private int currentIndex = -1;

        void startBuilding() {
            overlays.clear();
            currentIndex = -1;
        }

        boolean isEmpty() {
            return currentIndex < 0;
        }

        PixelData.Overlay current() {
            return currentIndex < 0 ? null : overlays.get(currentIndex);
        }

        void build(BlockState state, int opacity, byte light) {
            PixelData.Overlay current = current();
            if (current != null && current.state() == state) {
                increaseOpacity(opacity);
                return;
            }
            if (currentIndex < MAX_OVERLAYS - 1) {
                overlays.add(new PixelData.Overlay(state, (byte) Math.min(15, opacity), light));
                currentIndex++;
            } else {
                increaseOpacity(opacity);
            }
        }

        void increaseOpacity(int toAdd) {
            if (currentIndex < 0) {
                return;
            }
            PixelData.Overlay current = overlays.get(currentIndex);
            int added = Math.min(toAdd, 15);
            int value = Math.min(15, current.opacity() + added);
            overlays.set(currentIndex, new PixelData.Overlay(current.state(), (byte) value, current.light()));
        }

        List<PixelData.Overlay> finishBuilding() {
            return List.copyOf(overlays);
        }
    }
}
