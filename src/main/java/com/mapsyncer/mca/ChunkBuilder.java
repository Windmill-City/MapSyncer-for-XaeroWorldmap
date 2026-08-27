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
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
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
 * 仿照 Xaero WorldMap 的 {@code WorldDataReader.buildTile} 列扫描，为单个
 * {@code .mca} chunk 生成 16x16 的 {@link PixelData} 网格（[x][z] 顺序）。
 * 透明方块（水、玻璃、空气）作为 {@link PixelData.Overlay} 堆叠在顶部可见方块之上，
 * 与 Xaero 写入 {@code region.xaero} 像素负载的值（参数位域、顶部高度、叠加层、群系）一致。
 */
final class ChunkBuilder {

    /**
     * 一个已构建 chunk 的地图像素：位置 (x, z) 处的顶部可见方块状态及其绝对高度、
     * 其上最高的透明方块高度、以及该列捕获的光照等级。
     */
    record PixelData(
            BlockState state,
            short height,
            short topHeight,
            byte light,
            ResourceKey<Biome> biome,
            List<Overlay> overlays) {

        /**
         * 堆叠在像素之上的一个透明方块，如水面；包含其不透明度
         * （累积遮光值，0-15）和其第一个方块处捕获的光照等级，
         * 与 Xaero 的 {@code Overlay} 字节兼容。
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
     * 为一个 chunk 构建像素网格。当 chunk 缺失或生成程度不足
     * （状态低于 {@link ChunkStatus#FEATURES}）时返回 {@code null}。
     */
    static PixelData[][] build(
            CompoundTag chunk,
            int chunkX,
            int chunkZ,
            int caveStart,
            int caveDepth,
            ServerLevel level,
            HolderGetter<Block> blockLookup) {
        setupContext(level);

        boolean cave = caveStart != Integer.MAX_VALUE;

        ChunkStatus chunkStatus = readChunkStatus(chunk);
        if (chunkStatus == null || chunkStatus.getIndex() < ChunkStatus.BIOMES.getIndex()) {
            return null;
        }
        readBiomes(chunk);
        if (chunkStatus.getIndex() < ChunkStatus.FEATURES.getIndex()) {
            return null;
        }

        int chunkBottomY = chunk.getInt("yPos") * 16;
        resetColumnState();

        Heightmap heightmap = readHeightmap(chunk);
        ScanBounds bounds = computeScanBounds(cave, caveStart, caveDepth);
        ScanContext context = new ScanContext(
                chunkX,
                chunkZ,
                cave,
                caveStart,
                bounds.caveStartSectionHeight,
                bounds.lowH,
                heightmap,
                chunkBottomY,
                blockLookup);

        ListTag sectionsList = chunk.getList("sections", 10);
        if (!sectionsList.isEmpty()) {
            scanSections(context, sectionsList);
        }
        fillMissingPixels(context.pixels);
        return context.pixels;
    }

    /** 将共享的世界上下文存入扫描所用的静态字段。 */
    private static void setupContext(ServerLevel level) {
        ChunkBuilder.level = level;
        worldBottomY = level.getMinBuildHeight();
        worldTopY = level.getMaxBuildHeight();
        worldHasSkylight = level.dimensionType().hasSkyLight();
        biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
        ResourceKey<Biome> voidKey =
                ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("the_void"));
        voidBiomeKey = biomeRegistry.get(voidKey).isPresent() ? voidKey : null;
    }

    /** 读取 chunk 的生成状态，兼容 1.18 前带 {@code below_zero_retrogen} 的 chunk。 */
    private static ChunkStatus readChunkStatus(CompoundTag chunk) {
        boolean oldOptimizedChunk = chunk.contains("below_zero_retrogen");
        String status = oldOptimizedChunk
                ? chunk.getCompound("below_zero_retrogen").getString("target_status")
                : chunk.getString("Status");
        return ChunkStatus.byName(status);
    }

    /** 扫描开始前重置全部 256 列的状态数组。 */
    private static void resetColumnState() {
        for (int i = 0; i < HEIGHTMAP_ENTRIES; i++) {
            overlayBuilders[i].startBuilding();
            blockFound[i] = false;
            underair[i] = shouldEnterGround[i] = false;
            lightLevels[i] = 0;
            skyLightLevels[i] = (byte) (worldHasSkylight ? 15 : 0);
            topH[i] = worldBottomY;
            shouldExtendTillTheBottom[i] = false;
        }
    }

    /**
     * 高度图访问：要么是原始 int 数组（旧格式），要么是压缩的
     * {@link #heightMapBitArray} 位存储（新版 {@code Heightmaps} 标签）。
     */
    private record Heightmap(int[] values) {

        int value(int chunkBottomY, int pos2d) {
            return values != null ? values[pos2d] : chunkBottomY + heightMapBitArray.get(pos2d);
        }
    }

    /** 读取高度图，优先使用压缩的 {@code WORLD_SURFACE} 位数组。 */
    private static Heightmap readHeightmap(CompoundTag chunk) {
        if (!chunk.contains("Heightmaps", 10)) {
            int[] values = chunk.getIntArray("HeightMap");
            return values.length == HEIGHTMAP_ENTRIES ? new Heightmap(values) : null;
        }
        long[] heightMapArray = chunk.getCompound("Heightmaps").getLongArray("WORLD_SURFACE");
        int bitsPerHeight = heightMapArray.length / 4;
        if (bitsPerHeight <= 0 || bitsPerHeight > 10) {
            return null;
        }
        updateHeightArray(bitsPerHeight);
        System.arraycopy(heightMapArray, 0, heightMapBitArray.getRaw(), 0, heightMapArray.length);
        return new Heightmap(null);
    }

    /** 由洞穴参数推导出的纵向扫描边界。 */
    private record ScanBounds(int caveStartSectionHeight, int lowH) {}

    private static ScanBounds computeScanBounds(boolean cave, int caveStart, int caveDepth) {
        int caveStartSectionHeight = caveStart >> 4 << 4;
        int lowH = worldBottomY;
        if (cave && (lowH = caveStart + 1 - caveDepth) < worldBottomY) {
            lowH = worldBottomY;
        }
        return new ScanBounds(caveStartSectionHeight, lowH);
    }

    /** 单次 chunk 构建的不可变扫描配置与输出网格。 */
    private static final class ScanContext {

        private final PixelData[][] pixels = new PixelData[16][16];

        private final int chunkX;

        private final int chunkZ;

        private final boolean cave;

        private final int caveStart;

        private final int caveStartSectionHeight;

        private final int lowH;

        private final Heightmap heightmap;

        private final int chunkBottomY;

        private final HolderGetter<Block> blockLookup;

        ScanContext(
                int chunkX,
                int chunkZ,
                boolean cave,
                int caveStart,
                int caveStartSectionHeight,
                int lowH,
                Heightmap heightmap,
                int chunkBottomY,
                HolderGetter<Block> blockLookup) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.cave = cave;
            this.caveStart = caveStart;
            this.caveStartSectionHeight = caveStartSectionHeight;
            this.lowH = lowH;
            this.heightmap = heightmap;
            this.chunkBottomY = chunkBottomY;
            this.blockLookup = blockLookup;
        }
    }

    /** 可变的分区（section）状态：方块调色板、光照图与懒解码标志。 */
    private static final class SectionData {

        private final CompoundTag sectionCompound;

        private final int sectionHeight;

        private final boolean cave;

        private final CompoundTag blockStatesCompound;

        private final boolean hasBlocks;

        private boolean preparedSectionData;

        private boolean hasDifferentBlockStates;

        private byte[] lightMap;

        private byte[] skyLightMap;

        SectionData(CompoundTag sectionCompound, int lowHSection, boolean cave) {
            this.sectionCompound = sectionCompound;
            this.sectionHeight = sectionCompound.getByte("Y") * 16;
            this.cave = cave;
            this.blockStatesCompound =
                    sectionCompound.contains("block_states", 10) ? sectionCompound.getCompound("block_states") : null;
            boolean blocks = blockStatesCompound != null && sectionHeight >= lowHSection;
            if (blocks
                    && !(blocks = blockStatesCompound.contains("data", 12))
                    && blockStatesCompound.contains("palette", 9)) {
                ListTag paletteList = blockStatesCompound.getList("palette", 10);
                blocks = paletteList.size() == 1
                        && !((CompoundTag) paletteList.get(0))
                                .get("Name")
                                .getAsString()
                                .equals("minecraft:air");
            }
            this.hasBlocks = blocks;
        }

        /** 当该分区既无方块也无值得扫描的光照数据时返回 true。 */
        boolean shouldSkip(int sectionIndex) {
            return sectionIndex > 0
                    && !hasBlocks
                    && !sectionCompound.contains("BlockLight", 7)
                    && (!cave || !sectionCompound.contains("SkyLight", 7));
        }
    }

    /** 自上而下扫描所有分区，直到每列都找到其像素。 */
    private static void scanSections(ScanContext context, ListTag sectionsList) {
        int fillCounter = HEIGHTMAP_ENTRIES;
        int prevSectionHeight = Integer.MAX_VALUE;
        for (int i = sectionsList.size() - 1; i >= 0 && fillCounter > 0; i--) {
            SectionData data = new SectionData(sectionsList.getCompound(i), context.lowH >> 4 << 4, context.cave);
            if (data.shouldSkip(i)) {
                continue;
            }
            boolean previousSectionExists = prevSectionHeight - data.sectionHeight == 16;
            boolean underAirByDefault =
                    context.cave && !previousSectionExists && context.caveStartSectionHeight > data.sectionHeight;
            int sectionBasedHeight = data.sectionHeight + 15;
            prevSectionHeight = data.sectionHeight;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int pos2d = (z << 4) + x;
                    if (blockFound[pos2d]) {
                        continue;
                    }
                    if (scanColumn(context, data, x, z, pos2d, i, underAirByDefault, sectionBasedHeight)) {
                        fillCounter--;
                    }
                }
            }
        }
    }

    /**
     * 从起始高度向下扫描一列，直到找到像素。
     * 当该列已解析时返回 {@code true}。
     */
    private static boolean scanColumn(
            ScanContext context,
            SectionData data,
            int x,
            int z,
            int pos2d,
            int sectionIndex,
            boolean underAirByDefault,
            int sectionBasedHeight) {
        int heightMapValue =
                context.heightmap == null ? Integer.MIN_VALUE : context.heightmap.value(context.chunkBottomY, pos2d);
        int startHeight;
        if (context.cave) {
            startHeight = context.caveStart;
        } else {
            startHeight = heightMapValue < context.chunkBottomY ? sectionBasedHeight : heightMapValue + 3;
        }
        if (startHeight >= worldTopY) {
            startHeight = worldTopY - 1;
        }
        if (sectionIndex > 0 && ++startHeight < data.sectionHeight) {
            return false;
        }
        int localStartHeight = 15;
        if (startHeight >> 4 << 4 == data.sectionHeight) {
            localStartHeight = startHeight & 0xF;
        }
        prepareSectionData(data, context.blockLookup);
        if (underAirByDefault) {
            underair[pos2d] = true;
        }
        for (int y = localStartHeight; y >= 0; y--) {
            int h = data.sectionHeight | y;
            int pos = y << 8 | pos2d;
            BlockState state = blockStateAt(data, pos);
            mutablePos.set(context.chunkX << 4 | x, h, context.chunkZ << 4 | z);
            OverlayBuilder overlayBuilder = overlayBuilders[pos2d];
            if (!shouldExtendTillTheBottom[pos2d]
                    && !overlayBuilder.isEmpty()
                    && firstTransparentStateY[pos2d] - h >= 5) {
                shouldExtendTillTheBottom[pos2d] = true;
            }
            boolean buildResult = h >= context.lowH
                    && h < startHeight
                    && buildPixel(state, x, h, z, pos2d, context.cave, overlayBuilder);
            if (!buildResult && (y == 0 && sectionIndex == 0 || h <= context.lowH)) {
                resetToBottomPixel(pos2d, context.cave);
                h = worldBottomY;
                state = Blocks.AIR.defaultBlockState();
                buildResult = true;
            }
            if (buildResult) {
                recordPixel(context.pixels, state, x, z, h, pos2d, context.cave, overlayBuilder);
                return true;
            }
            updateColumnLight(pos2d, pos, data, context, startHeight, heightMapValue);
        }
        return false;
    }

    /** 读取分区的方块调色板与光照图，每个分区至多执行一次。 */
    private static void prepareSectionData(SectionData data, HolderGetter<Block> blockLookup) {
        if (data.preparedSectionData) {
            return;
        }
        if (data.hasBlocks) {
            ListTag paletteList = data.blockStatesCompound.getList("palette", 10);
            data.hasDifferentBlockStates = data.blockStatesCompound.contains("data", 12) && paletteList.size() > 1;
            boolean shouldReadPalette = true;
            if (data.hasDifferentBlockStates) {
                long[] blockStatesArray = data.blockStatesCompound.getLongArray("data");
                int bits = blockStatesArray.length * 64 / 4096;
                int bitsOther = Math.max(4, Mth.ceillog2(paletteList.size()));
                if (bitsOther > 8) {
                    bits = bitsOther;
                }
                if (bits < 2) {
                    data.hasDifferentBlockStates = false;
                    shouldReadPalette = false;
                } else {
                    if (blockStatesBitArray == null || blockStatesBitArray.getBits() != bits) {
                        blockStatesBitArray = new SimpleBitStorage(bits, 4096);
                    }
                    if (blockStatesArray.length == blockStatesBitArray.getRaw().length) {
                        System.arraycopy(blockStatesArray, 0, blockStatesBitArray.getRaw(), 0, blockStatesArray.length);
                    } else {
                        data.hasDifferentBlockStates = false;
                        shouldReadPalette = false;
                    }
                }
            }
            blockStatePalette.clear();
            if (shouldReadPalette) {
                for (Tag stateTag : paletteList) {
                    blockStatePalette.add(NbtUtils.readBlockState(blockLookup, (CompoundTag) stateTag));
                }
            }
        }
        if (data.sectionCompound.contains("BlockLight", 7)
                && (data.lightMap = data.sectionCompound.getByteArray("BlockLight")).length != 2048) {
            data.lightMap = null;
        }
        if (data.cave
                && data.sectionCompound.contains("SkyLight", 7)
                && (data.skyLightMap = data.sectionCompound.getByteArray("SkyLight")).length != 2048) {
            data.skyLightMap = null;
        }
        data.preparedSectionData = true;
    }

    /** 从调色板解析分区内部索引处的 {@link BlockState}。 */
    private static BlockState blockStateAt(SectionData data, int pos) {
        BlockState state = null;
        if (data.hasBlocks) {
            int indexInPalette = data.hasDifferentBlockStates ? blockStatesBitArray.get(pos) : 0;
            if (indexInPalette < blockStatePalette.size()) {
                state = blockStatePalette.get(indexInPalette);
            }
        }
        return state == null ? Blocks.AIR.defaultBlockState() : state;
    }

    /** 当扫描触及底部时，将一列重置为空的底部像素。 */
    private static void resetToBottomPixel(int pos2d, boolean cave) {
        lightLevels[pos2d] = 0;
        if (cave) {
            skyLightLevels[pos2d] = 0;
        }
    }

    /** 将找到的像素写入输出网格并锁定该列。 */
    private static void recordPixel(
            PixelData[][] pixels,
            BlockState state,
            int x,
            int z,
            int h,
            int pos2d,
            boolean cave,
            OverlayBuilder overlayBuilder) {
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
    }

    /** 捕获被扫描方块的方块/天空光照，供叠加层构建使用。 */
    private static void updateColumnLight(
            int pos2d, int pos, SectionData data, ScanContext context, int startHeight, int heightMapValue) {
        byte dataLight = data.lightMap == null ? 0 : nibbleValue(data.lightMap, pos);
        if (context.cave && dataLight < 15 && worldHasSkylight) {
            int dataSkyLight = startHeight > heightMapValue
                    ? 15
                    : (data.skyLightMap == null ? 0 : nibbleValue(data.skyLightMap, pos));
            skyLightLevels[pos2d] = (byte) dataSkyLight;
        }
        lightLevels[pos2d] = dataLight;
    }

    /** 将任何未解析的列用空白空气像素填充。 */
    private static void fillMissingPixels(PixelData[][] pixels) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (pixels[x][z] == null) {
                    pixels[x][z] = airPixel();
                }
            }
        }
    }

    private static PixelData airPixel() {
        return new PixelData(
                Blocks.AIR.defaultBlockState(), (short) worldBottomY, (short) worldBottomY, (byte) 0, null, List.of());
    }

    private static boolean buildPixel(
            BlockState state, int x, int h, int z, int pos2d, boolean cave, OverlayBuilder overlayBuilder) {
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
        boolean isFlower = b instanceof PitcherCropBlock
                || b instanceof TallFlowerBlock
                || b instanceof FlowerBlock
                || state.is(BlockTags.FLOWERS) && !state.is(BlockTags.LEAVES);
        if (b instanceof DoublePlantBlock && !isFlower) {
            return true;
        }
        return buggedStates.contains(state);
    }

    private static boolean shouldOverlay(StateHolder<?, ?> state) {
        if (state instanceof BlockState blockState) {
            if (blockState.getBlock() instanceof AirBlock || blockState.getBlock() instanceof GlassBlock) {
                return true;
            }
            Block b = blockState.getBlock();
            return b == Blocks.ICE
                    || b == Blocks.NETHER_PORTAL
                    || b == Blocks.TINTED_GLASS
                    || b instanceof StainedGlassBlock
                    || b instanceof StainedGlassPaneBlock;
        }
        FluidState fluidState = (FluidState) state;
        return !fluidState.is(FluidTags.LAVA);
    }

    private static boolean hasVanillaColor(BlockState state) {
        MapColor color = null;
        try {
            color = state.getMapColor(level, mutablePos);
        } catch (Throwable t) {
            buggedStates.add(state);
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

    /** 分区内 4x4x4 群系网格，对应 Xaero 的 {@code WorldDataReaderSectionBiomeData}。 */
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
     * 逐列叠加层累加器，对应 Xaero 的 {@code OverlayBuilder}：同一状态的连续
     * 透明方块合并为一个叠加层，其不透明度累加（上限 15），光照取该连续段的
     * 第一个方块处捕获的值。
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
