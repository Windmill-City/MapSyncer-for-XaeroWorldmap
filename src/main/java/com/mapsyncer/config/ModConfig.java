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

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConfig.class);

    public record ForgeConfig(ServerConfig config, ForgeConfigSpec spec) {
    }

    public static final ForgeConfig Config;

    private static final Map<String, LayerPlan> Plans = Map.of();

    public static LayerPlan getPlan(String dimId) {
        return Plans.getOrDefault(dimId, new LayerPlan());
    }

    private static List<String> getDefaultPlans() {
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
        Config = new ForgeConfig(pair.getLeft(), pair.getRight());
        rebuildPlans();
    }

    private static void rebuildPlans() {
        Plans.clear();
        for (var entryStr : Config.config().dimensionConfigs.get()) {
            if (entryStr == null || entryStr.isBlank()) {
                continue;
            }
            var entry = parsePlanEntry(entryStr);
            if (entry != null) {
                Plans.put(entry.getKey(), entry.getValue());
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
        modConfig = config;
    }

    public static void reloadFromDisk() {
        if (modConfig != null) {
            try {
                Path path = modConfig.getFullPath();
                CommentedFileConfig file = CommentedFileConfig.of(path);
                file.load();
                Config.spec().acceptConfig(file);
                rebuildPlans();
            } finally {
                file.close();
            }
        }
    }

    private static net.minecraftforge.fml.config.ModConfig modConfig;

    public static class ServerConfig {

        public final ConfigValue<Boolean> AutoUpdate;

        public final ConfigValue<List<? extends String>> Plans;

        public ServerConfig(ForgeConfigSpec.Builder builder) {
            builder.push("automatic_update");
            builder.comment("Automatic update settings");

            AutoUpdate = builder.comment(
                    "Enable automatic updates: run when no players are online")
                    .define("automaticUpdateEnabled", true);

            builder.pop();

            builder.push("dimension_scan");
            builder.comment("Dimension scan settings");

            Plans = builder.comment(
                    "Per-dimension scan configuration list (one line per dimension)",
                    "Format per entry: \"dimension = layerPlan\"",
                    "layerPlan: SURFACE, explicit Y (e.g. 63), or combos (e.g. SURFACE,63)",
                    "Example: \"minecraft:overworld = SURFACE\"")
                    .defineList("dimension_configs", getDefaultPlans(), obj -> obj instanceof String);

            builder.pop();
        }
    }
}
