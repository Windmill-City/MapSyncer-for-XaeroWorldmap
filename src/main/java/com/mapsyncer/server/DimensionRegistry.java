package com.mapsyncer.server;

import com.mapsyncer.config.DimensionScanConfig;
import com.mapsyncer.config.LayerPlan;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.util.ApiHelper;
import java.util.*;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DimensionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionRegistry.class);

    private static volatile boolean hasRegistered = false;

    public static void registerAllDimensions(MinecraftServer server) {

        if (hasRegistered) {
            LOGGER.debug("Dimensions already registered, skipping");
            return;
        }

        LOGGER.info("Starting dimension registration on first map generation...");

        List<? extends String> currentConfigs = new ArrayList<>(ModConfig.SERVER.dimensionConfigs.get());

        Set<String> configuredDimensions = new HashSet<>();
        for (DimensionScanConfig config : ModConfig.SERVER.parseDimensionConfigs()) {
            configuredDimensions.add(normalizeDimensionId(config.dimension()));
        }

        LOGGER.info("Currently configured dimensions: {}", configuredDimensions);

        Set<String> newDimensions = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimKey = level.dimension();
            String dimId = ApiHelper.getDimId(dimKey);

            String normalizedId = normalizeDimensionId(dimId);

            if (!configuredDimensions.contains(normalizedId)) {

                newDimensions.add(dimId);
                LOGGER.debug("Found unconfigured dimension: {} (normalized: {})", dimId, normalizedId);
            }
        }

        if (newDimensions.isEmpty()) {
            LOGGER.info("All dimensions already configured, no updates needed");
            hasRegistered = true;
            return;
        }

        List<String> updatedConfigs = new ArrayList<>(currentConfigs);

        for (String dimId : newDimensions) {

            ServerLevel level = getLevelForDimension(server, dimId);
            DimensionTypeInfo dimTypeInfo;
            if (level != null) {
                dimTypeInfo = ApiHelper.fromDimensionType(level.dimensionType());
                LOGGER.info(
                        "Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, height={}",
                        dimId,
                        dimTypeInfo.hasSkylight(),
                        dimTypeInfo.hasCeiling(),
                        dimTypeInfo.minY(),
                        dimTypeInfo.height());
            } else {

                dimTypeInfo = DimensionTypeInfo.fromDimensionId(dimId);
            }

            DimensionScanConfig finalConfig = new DimensionScanConfig(dimId, LayerPlan.surfaceOnly(), dimTypeInfo);

            String configStr = configToString(finalConfig);
            updatedConfigs.add(configStr);
            LOGGER.info(
                    "Added dimension config: {} (layerPlan={}, hasSkylight={})",
                    dimId,
                    finalConfig.layerPlan().toConfigString(),
                    dimTypeInfo.hasSkylight());
        }

        ModConfig.SERVER.dimensionConfigs.set(updatedConfigs);

        ModConfig.SERVER_SPEC.save();

        hasRegistered = true;
        LOGGER.info(
                "Dimension registration completed: {} new dimensions added, total {} dimensions configured",
                newDimensions.size(),
                updatedConfigs.size());
    }

    public static void resetRegistration() {
        hasRegistered = false;
        LOGGER.info("Dimension registration state reset");
    }

    private static String normalizeDimensionId(String dimId) {
        return dimId.replace("minecraft:", "").toLowerCase();
    }

    private static @Nullable ServerLevel getLevelForDimension(MinecraftServer server, String dimId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (ApiHelper.getDimId(level.dimension()).equals(dimId)) {
                return level;
            }
        }
        return null;
    }

    private static String configToString(DimensionScanConfig config) {
        return config.dimension() + "|" + config.layerPlan().toConfigString();
    }
}
