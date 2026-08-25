package com.mapsyncer.client;

import java.nio.file.Path;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.file.MapSaveLoad;
import xaero.map.region.LeveledRegion;
import xaero.map.region.MapRegion;

public final class XaeroBridge {

    private static final Logger LOGGER = LogManager.getLogger(XaeroBridge.class);

    private static final byte LOAD_STATE_CLEARED = 4;

    private static volatile boolean initialized = false;

    public static boolean initialize() {
        try {
            Class.forName("xaero.map.WorldMapSession");
            Class.forName("xaero.map.MapProcessor");
            Class.forName("xaero.map.file.MapSaveLoad");
            Class.forName("xaero.map.region.MapRegion");
            Class.forName("xaero.map.region.LeveledRegion");
            initialized = true;
            LOGGER.info("Xaero WorldMap bridge initialized");
            return true;
        } catch (ClassNotFoundException e) {
            LOGGER.error("Unable to load Xaero WorldMap classes:", e);
            initialized = false;
            return false;
        }
    }

    private static WorldMapSession getSession() {
        return WorldMapSession.getCurrentSession();
    }

    private static MapProcessor getMapProcessor() {
        WorldMapSession session = getSession();
        if (session == null) return null;
        return session.getMapProcessor();
    }

    private static MapSaveLoad getMapSaveLoad() {
        MapProcessor processor = getMapProcessor();
        if (processor == null) return null;
        return processor.getMapSaveLoad();
    }

    private static MapRegion getLeafMapRegion(int cave, int regionX, int regionZ, boolean createIfMissing) {
        MapProcessor processor = getMapProcessor();
        if (processor == null) return null;
        return processor.getLeafMapRegion(cave, regionX, regionZ, createIfMissing);
    }

    private static boolean requestLoad(Object mapRegion, String reason, boolean prioritize) {
        if (!(mapRegion instanceof MapRegion region)) return false;
        MapSaveLoad saveLoad = getMapSaveLoad();
        if (saveLoad == null) return false;
        saveLoad.requestLoad(region, reason, prioritize);
        return true;
    }

    private static boolean cancelRefresh(Object mapRegion) {
        if (!(mapRegion instanceof MapRegion region)) return false;
        MapProcessor processor = getMapProcessor();
        if (processor == null) return false;
        region.cancelRefresh(processor);
        return true;
    }

    private static boolean setLoadState(Object mapRegion, byte state) {
        if (!(mapRegion instanceof MapRegion region)) return false;
        region.setLoadState(state);
        return true;
    }

    private static boolean setShouldCache(Object mapRegion, boolean value) {
        if (!(mapRegion instanceof LeveledRegion<?> region)) return false;
        region.setShouldCache(value, "mapsyncer");
        return true;
    }

    private static boolean setHasHadTerrain(Object mapRegion) {
        if (!(mapRegion instanceof MapRegion region)) return false;
        region.setHasHadTerrain();
        return true;
    }

    private static ResourceKey<Level> toDimensionKey(String dimId) {
        ResourceLocation location = ResourceLocation.tryParse(dimId);
        if (location == null) {
            return null;
        }
        return ResourceKey.create(Registries.DIMENSION, location);
    }

    public static String getCurrentWorldId() {
        if (!initialized) return null;
        MapProcessor processor = getMapProcessor();
        if (processor == null) return null;
        return processor.getCurrentWorldId();
    }

    public static Path getCurrentServerDirectory() {
        if (!initialized) return null;
        String worldId = getCurrentWorldId();
        if (worldId == null || worldId.isEmpty()) return null;
        return MapSaveLoad.getRootFolder(worldId);
    }

    public static String getDimensionName(String dimId) {
        if (!initialized) return null;
        MapProcessor processor = getMapProcessor();
        if (processor == null) return null;
        ResourceKey<Level> key = toDimensionKey(dimId);
        if (key == null) return null;
        return processor.getDimensionName(key);
    }

    public static void loadRegion(int regionX, int regionZ, int cave) {
        if (!initialized) return;
        try {
            Object mapRegion = getLeafMapRegion(cave, regionX, regionZ, true);
            if (mapRegion == null) {
                LOGGER.warn("Cannot create MapRegion ({}, {}) layer={}", regionX, regionZ, cave);
                return;
            }

            if (!cancelRefresh(mapRegion) || !setShouldCache(mapRegion, true) || !setHasHadTerrain(mapRegion)) {
                LOGGER.warn("Region ({}, {}) layer={} load preparation failed", regionX, regionZ, cave);
                return;
            }

            if (!setLoadState(mapRegion, LOAD_STATE_CLEARED)) {
                LOGGER.warn("Region ({}, {}) layer={} setLoadState failed", regionX, regionZ, cave);
                return;
            }

            if (!requestLoad(mapRegion, "sync", false)) {
                LOGGER.warn("Region ({}, {}) layer={} requestLoad failed", regionX, regionZ, cave);
                return;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load region ({}, {}) layer={}", regionX, regionZ, cave, e);
        }
    }

    public static void onWorldIdChanged() {
        PacketHandler.onXaeroWorldContextReady();
    }
}
