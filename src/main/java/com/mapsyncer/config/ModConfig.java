package com.mapsyncer.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.LoggerFactory;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;

public class ModConfig {

    public record ForgeConfig(ServerConfig config, ForgeConfigSpec spec) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConfig.class);

    private static Map<String, LayerPlan> plans = Map.of();

    public static final ForgeConfig SERVER;

    public static LayerPlan getPlan(String dimId) {
        return plans.getOrDefault(dimId, new LayerPlan());
    }

    private static List<String> getDefaultDimensionConfigStrings() {
        var defaults = new LinkedHashMap<>();
        defaults.put("minecraft:overworld", new LayerPlan(true, List.of()));
        defaults.put("minecraft:the_nether", new LayerPlan(false, List.of(64)));
        defaults.put("minecraft:the_end", new LayerPlan(true, List.of()));
        return defaults.entrySet().stream()
                .map(Map.Entry::toString)
                .toList();
    }

    static {
        var pair = new ForgeConfigSpec.Builder().configure(ServerConfig::new);
        SERVER = new ForgeConfig(pair.getLeft(), pair.getRight());
        rebuildLayerPlans();
    }

    private static void rebuildLayerPlans() {
        plans.clear();
        for (var entryStr : SERVER.config().dimensionConfigs.get()) {
            if (entryStr == null || entryStr.isBlank()) {
                continue;
            }
            var entry = parsePlanEntry(entryStr);
            if (entry != null) {
                plans.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static Map.Entry<String, LayerPlan> parsePlanEntry(String configStr) {
        if (configStr == null || configStr.isBlank()) {
            return null;
        }
        String trimmed = configStr.trim();
        int eq = trimmed.indexOf('=');
        String dimension;
        String planStr;
        if (eq > 0) {
            dimension = trimmed.substring(0, eq).trim();
            planStr = trimmed.substring(eq + 1).trim();
        } else {
            LOGGER.warn("Invalid dimension config: [{}]", configStr);
            return null;
        }
        return Map.entry(dimension, LayerPlan.parse(planStr));
    }

    public static void bindServerConfig(net.minecraftforge.fml.config.ModConfig config) {
        if (config.getType() == net.minecraftforge.fml.config.ModConfig.Type.SERVER) {
            modConfig = config;
        }
    }

    public static void reloadFromDisk() {
        if (modConfig != null) {
            try {
                Path path = modConfig.getFullPath();
                CommentedFileConfig file = CommentedFileConfig.of(path);
                file.load();
                SERVER.spec().acceptConfig(file);
                rebuildLayerPlans();
            } finally {
                file.close();
            }
        }
    }

    private static net.minecraftforge.fml.config.ModConfig modConfig;

    public static class ServerConfig {

        public final ConfigValue<Boolean> automaticUpdateEnabled;

        public final ConfigValue<List<? extends String>> plans;

        public ServerConfig(ForgeConfigSpec.Builder builder) {
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
                    "Format per entry: \"dimension = layerPlan\"",
                    "layerPlan: SURFACE, explicit Y (e.g. 63), or combos (e.g. SURFACE,63)",
                    "Example: \"minecraft:overworld = SURFACE\"")
                    .defineList("dimension_configs", getDefaultDimensionConfigStrings(), obj -> obj instanceof String);

            builder.pop();
        }
    }
}
