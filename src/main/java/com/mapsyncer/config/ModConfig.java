package com.mapsyncer.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.common.ForgeConfigSpec.EnumValue;
import net.minecraftforge.common.ForgeConfigSpec.IntValue;

public class ModConfig {

    public static final int MAX_BLOCK_PROPERTIES_CACHE = 10000;

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

    public static final ForgeConfigSpec SERVER_SPEC;

    public static final ServerConfig SERVER;

    private static List<String> getDefaultDimensionConfigStrings() {
        return DimensionConfigParser.getDefaultDimensionConfigStrings();
    }

    static {
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

    private static volatile @Nullable net.minecraftforge.fml.config.ModConfig boundServerConfig;

    public static class ServerConfig {

        public final EnumValue<UpdateMode> incrementalUpdateMode;

        public final EnumValue<LayerPlan.ScanMode> defaultScanMode;

        public final IntValue defaultCaveStart;

        public final ConfigValue<List<? extends String>> dimensionConfigs;

        public ServerConfig(ForgeConfigSpec.Builder builder) {
            builder.push("general");
            builder.comment("通用设置 / General settings");

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
