package com.mapsyncer.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Path;
import java.util.List;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.BooleanValue;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.common.ForgeConfigSpec.EnumValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;

public class ModConfig {

    public static final int MAX_REGION_META_CACHE = 50000;
    public static final int MAX_BLOCK_PROPERTIES_CACHE = 10000;

    public static final long TASK_TIMEOUT_SECONDS = 60;

    public static final int MAX_CONCURRENT_REGIONS = 16;

    public static int resolveConcurrentRegions(int configured) {
        if (configured > 0) {
            return Math.max(1, Math.min(MAX_CONCURRENT_REGIONS, configured));
        }
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(MAX_CONCURRENT_REGIONS, processors - 2));
    }

    public static Path outputDir(Path baseOutputDir, int caveLayer) {
        if (caveLayer == Integer.MAX_VALUE) {
            return baseOutputDir;
        }
        return baseOutputDir.resolve("caves").resolve(String.valueOf(caveLayer));
    }

    public static String relativePath(String xaeroDimName, int caveLayer, int regionX, int regionZ) {
        if (caveLayer == Integer.MAX_VALUE) {
            return xaeroDimName + "/" + regionX + "_" + regionZ;
        }
        return xaeroDimName + "/caves/" + caveLayer + "/" + regionX + "_" + regionZ;
    }

    public static final ForgeConfigSpec CLIENT_SPEC;

    public static final ClientConfig CLIENT;

    public static final ForgeConfigSpec SERVER_SPEC;

    public static final ServerConfig SERVER;

    private static List<String> getDefaultDimensionConfigStrings() {
        return DimensionConfigParser.getDefaultDimensionConfigStrings();
    }

    static {
        var clientPair = new ForgeConfigSpec.Builder().configure(ClientConfig::new);
        CLIENT = clientPair.getLeft();
        CLIENT_SPEC = clientPair.getRight();

        var serverPair = new ForgeConfigSpec.Builder().configure(ServerConfig::new);
        SERVER = serverPair.getLeft();
        SERVER_SPEC = serverPair.getRight();
    }

    public static void bindServerConfig(net.minecraftforge.fml.config.ModConfig config) {
        if (config.getType() == net.minecraftforge.fml.config.ModConfig.Type.SERVER) {
            boundServerConfig = config;
        }
    }

    public static void reloadServerFromDisk() {
        if (boundServerConfig != null) {
            Path path = boundServerConfig.getFullPath();
            CommentedFileConfig disk = CommentedFileConfig.of(path);
            disk.load();
            try {
                SERVER_SPEC.acceptConfig(disk);
            } finally {
                disk.close();
            }
        }
        DimensionConfigParser.invalidateCache();
    }

    private static volatile net.minecraftforge.fml.config.ModConfig boundServerConfig;

    public static class ClientConfig {

        public final IntValue hashThreads;

        public ClientConfig(ForgeConfigSpec.Builder builder) {
            builder.push("client");
            builder.comment("客户端设置 / Client settings");

            int defaultThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            int maxThreads = Runtime.getRuntime().availableProcessors();

            hashThreads = builder.comment(
                            "哈希计算线程数（用于地图同步时的并行计算）",
                            "Number of threads for hash computation during map sync",
                            "",
                            "默认使用可用处理器数的一半，避免阻塞游戏主线程",
                            "Default uses half of available processors to avoid blocking game main thread",
                            "",
                            "线程数选择建议：",
                            "  1-2 核：使用 1 线程",
                            "  4 核：使用 2 线程（大多数配置的默认值）",
                            "  8+ 核：使用 4-8 线程加快同步速度",
                            "Thread count recommendations:",
                            "  1-2 cores: use 1 thread",
                            "  4 cores: use 2 threads (default for most setups)",
                            "  8+ cores: use 4-8 threads for faster sync",
                            "",
                            "默认：" + defaultThreads + "（可用 " + maxThreads + " 个处理器的一半）",
                            "Default: " + defaultThreads + " (half of " + maxThreads + " available processors)",
                            "范围：1 - " + maxThreads,
                            "Range: 1 - " + maxThreads)
                    .defineInRange("hashThreads", defaultThreads, 1, maxThreads);

            builder.pop();
        }

        public int getHashThreads() {
            return hashThreads.get();
        }
    }

    public static class ServerConfig {

        public final BooleanValue enableDebugLogging;

        public final IntValue maxConcurrentRegions;

        public final IntValue maxSyncPacketSize;

        public final EnumValue<UpdateMode> incrementalUpdateMode;

        public final EnumValue<LayerPlan.ScanMode> defaultScanMode;

        public final IntValue defaultCaveStart;

        public final ConfigValue<List<? extends String>> dimensionConfigs;

        public ServerConfig(ForgeConfigSpec.Builder builder) {
            builder.push("general");
            builder.comment("通用设置 / General settings");

            enableDebugLogging = builder.comment("启用调试日志记录（用于地图生成过程调试）", "Enable debug logging for map generation")
                    .define("enableDebugLogging", false);
            maxConcurrentRegions = builder.comment(
                            "同时转换的最大区域数；0 = 自动（逻辑处理器数 - 2，最小 1，最大 16）",
                            "Max regions to convert concurrently; 0 = auto (logical CPUs - 2, min 1, max 16)")
                    .defineInRange("maxConcurrentRegions", 0, 0, 16);
            maxSyncPacketSize = builder.comment(
                            "同步数据包最大字节数",
                            "Maximum sync packet size in bytes",
                            "",
                            "大小选项供快速参考（均能被 1024KB/s 整除）：",
                            "  65536  = 64KB  （保守，1024KB/s 时每秒 16 包）",
                            "  131072 = 128KB （平衡，1024KB/s 时每秒 8 包）",
                            "  262144 = 256KB （推荐，1024KB/s 时每秒 4 包）",
                            "  524288 = 512KB （高效，1024KB/s 时每秒 2 包）",
                            "  1048576 = 1MB  （最大，1024KB/s 时每秒 1 包）",
                            "默认：256KB（推荐），范围：64KB - 1MB",
                            "",
                            "Size options for quick reference (all divide 1024KB/s evenly):",
                            "  65536  = 64KB  (conservative, 16 packets/s at 1024KB/s)",
                            "  131072 = 128KB (balanced, 8 packets/s at 1024KB/s)",
                            "  262144 = 256KB (recommended, 4 packets/s at 1024KB/s)",
                            "  524288 = 512KB (efficient, 2 packets/s at 1024KB/s)",
                            "  1048576 = 1MB  (maximum, 1 packet/s at 1024KB/s)",
                            "Default: 256KB (recommended), Range: 64KB - 1MB")
                    .defineInRange("maxSyncPacketSize", 262144, 65536, 1048576);

            builder.pop();

            builder.push("incremental_update");
            builder.comment("增量更新设置 / Incremental update settings");

            incrementalUpdateMode = builder.comment(
                            "增量更新模式：DISABLED（禁用），ON_EMPTY（无人在线时更新）",
                            "Incremental update mode: DISABLED (off), ON_EMPTY (run when no players are online)")
                    .defineEnum("incrementalUpdateMode", UpdateMode.DISABLED);

            builder.pop();

            builder.push("dimension_scan");
            builder.comment("维度扫描设置 / Dimension scan settings");

            defaultScanMode = builder.comment(
                            "未在 dimension_configs 中的维度的默认层计划（SURFACE=仅地表，CAVE=单层洞穴）",
                            "Default layer plan fallback for dimensions not in dimension_configs",
                            "SURFACE = surface only; CAVE = single cave layer at default_cave_start")
                    .defineEnum("default_scan_mode", LayerPlan.ScanMode.SURFACE);

            defaultCaveStart = builder.comment(
                            "default_scan_mode=CAVE 时的 caveStart Y（对应 caves(Y) 层计划）",
                            "Cave start Y when default_scan_mode=CAVE (maps to layerPlan caves(Y))")
                    .defineInRange("default_cave_start", 63, -512, 512);

            dimensionConfigs = builder.comment(
                            "维度扫描配置列表（每个维度一条字符串）",
                            "推荐：\"dimension = layerPlan\"",
                            "layerPlan：SURFACE、ALL、显式 Y（如 63）或组合（如 SURFACE,63）",
                            "示例：\"minecraft:the_nether = SURFACE,63\"",
                            "旧格式 \"dimension|layerPlan\" / \"dimension|SURFACE|63|…\" 仍可读取",
                            "Per-dimension scan configuration list (one string per dimension)",
                            "Preferred: \"dimension = layerPlan\"",
                            "layerPlan: SURFACE, ALL, explicit Y (e.g. 63), or combos (e.g. SURFACE,63)",
                            "Example: \"minecraft:the_nether = SURFACE,63\"",
                            "Legacy \"dimension|layerPlan\" and \"dimension|SURFACE|63|…\" still accepted")
                    .defineList("dimension_configs", getDefaultDimensionConfigStrings(), obj -> obj instanceof String);

            builder.pop();
        }

        public List<DimensionScanConfig> parseDimensionConfigs() {
            return DimensionConfigParser.parseDimensionConfigs(dimensionConfigs.get());
        }

        public DimensionScanConfig getConfigForDimension(String dimensionPath) {
            return DimensionConfigParser.getConfigForDimension(
                    dimensionPath, dimensionConfigs.get(), defaultScanMode.get(), defaultCaveStart.get());
        }
    }
}
