package com.mapsyncer.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModConfig {

    public record ForgeConfig(ServerConfig config, ForgeConfigSpec spec) {}

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConfig.class);

    private static Map<String, LayerPlan> layerPlans = Map.of();

    public static final ForgeConfig SERVER;

    public static LayerPlan getPlan(String dimId) {
        return layerPlans.getOrDefault(dimId, new LayerPlan());
    }

    private static List<String> getDefaultDimensionConfigStrings() {
        Map<String, LayerPlan> defaults = new LinkedHashMap<>();
        defaults.put("minecraft:overworld", new LayerPlan(true, List.of()));
        defaults.put("minecraft:the_nether", new LayerPlan(false, List.of(LayerPlan.DEFAULT_CAVE_START)));
        defaults.put("minecraft:the_end", new LayerPlan(true, List.of()));
        return List.of(defaults.toString());
    }

    static {
        var pair = new ForgeConfigSpec.Builder().configure(ServerConfig::new);
        SERVER = new ForgeConfig(pair.getLeft(), pair.getRight());
        rebuildLayerPlans();
    }

    private static void rebuildLayerPlans() {
        Map<String, LayerPlan> map = new LinkedHashMap<>();
        for (String configStr : SERVER.config().dimensionConfigs.get()) {
            if (configStr == null || configStr.isBlank()) {
                continue;
            }
            String trimmed = configStr.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                for (String entry : trimmed.substring(1, trimmed.length() - 1).split(",\\s+")) {
                    putPlanEntry(map, entry, configStr);
                }
            } else {
                putPlanEntry(map, trimmed, configStr);
            }
        }
        layerPlans = Map.copyOf(map);
    }

    private static void putPlanEntry(Map<String, LayerPlan> map, String entry, String configStr) {
        if (entry == null || entry.isBlank()) {
            return;
        }
        int eq = entry.indexOf('=');
        String dimension;
        String planStr;
        if (eq >= 0) {
            dimension = entry.substring(0, eq).trim();
            planStr = entry.substring(eq + 1).trim();
        } else {
            dimension = entry.trim();
            planStr = "";
        }
        if (dimension.isEmpty()) {
            LOGGER.warn("Invalid dimension config (empty dimension): [{}]", configStr);
            return;
        }
        map.put(dimension, planStr.isEmpty() ? new LayerPlan(false, List.of()) : LayerPlan.parse(planStr));
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
                SERVER.spec().acceptConfig(disk);
                rebuildLayerPlans();
            } finally {
                disk.close();
            }
        }
    }

    private static volatile net.minecraftforge.fml.config.ModConfig boundServerConfig;

    public static class ServerConfig {

        public final ConfigValue<Boolean> automaticUpdateEnabled;

        public final ConfigValue<List<? extends String>> plans;

        public ServerConfig(ForgeConfigSpec.Builder builder) {
            builder.push("general");
            builder.comment("General settings");

            builder.pop();

            builder.push("automatic_update");
            builder.comment("Automatic update settings");

            automaticUpdateEnabled = builder.comment(
                    "Enable automatic updates: run when no players are online")
                    .define("automaticUpdateEnabled", true);

            builder.pop();

            builder.push("dimension_scan");
            builder.comment("Dimension scan settings");

            dimensionConfigs = builder.comment(
                    "Per-dimension scan configuration list (one string per dimension)",
                    "Preferred format: \"dimension = layerPlan\"",
                    "layerPlan: SURFACE, explicit Y (e.g. 63), or combos (e.g. SURFACE,63)",
                    "Example: \"minecraft:the_nether = SURFACE,63\"")
                    .defineList("dimension_configs", getDefaultDimensionConfigStrings(), obj -> obj instanceof String);

            builder.pop();
        }
    }
}
