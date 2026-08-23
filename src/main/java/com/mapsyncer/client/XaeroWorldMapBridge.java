package com.mapsyncer.client;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.file.MapSaveLoad;
import xaero.map.region.LeveledRegion;
import xaero.map.region.MapRegion;

public final class XaeroWorldMapBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroWorldMapBridge.class);

    public static final byte LOAD_STATE_CLEARED = 4;

    private static volatile boolean initialized = false;

    private static volatile WorldMapSession cachedSession;
    private static volatile MapProcessor cachedMapProcessor;
    private static volatile MapSaveLoad cachedMapSaveLoad;

    private static final ConcurrentHashMap<String, String> dimNameCache = new ConcurrentHashMap<>();

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

    public static WorldMapSession getSession() {
        if (!initialized) return null;
        cachedSession = WorldMapSession.getCurrentSession();
        return cachedSession;
    }

    public static MapProcessor getMapProcessor() {
        if (!initialized) return null;
        if (cachedMapProcessor == null) {
            WorldMapSession session = getSession();
            if (session == null) return null;
            cachedMapProcessor = session.getMapProcessor();
        }
        return cachedMapProcessor;
    }

    public static MapSaveLoad getMapSaveLoad() {
        if (!initialized) return null;
        if (cachedMapSaveLoad == null) {
            MapProcessor processor = getMapProcessor();
            if (processor == null) return null;
            cachedMapSaveLoad = processor.getMapSaveLoad();
        }
        return cachedMapSaveLoad;
    }

    public static MapRegion getLeafMapRegion(int caveLayer, int regionX, int regionZ, boolean createIfMissing) {
        if (!initialized) return null;
        MapProcessor processor = getMapProcessor();
        if (processor == null) return null;
        return processor.getLeafMapRegion(caveLayer, regionX, regionZ, createIfMissing);
    }

    public static boolean requestLoad(Object mapRegion, String reason, boolean prioritize) {
        if (!initialized || !(mapRegion instanceof MapRegion region)) return false;
        MapSaveLoad saveLoad = getMapSaveLoad();
        if (saveLoad == null) return false;
        saveLoad.requestLoad(region, reason, prioritize);
        return true;
    }

    public static boolean cancelRefresh(Object mapRegion) {
        if (!initialized || !(mapRegion instanceof MapRegion region)) return false;
        MapProcessor processor = getMapProcessor();
        if (processor == null) return false;
        region.cancelRefresh(processor);
        return true;
    }

    public static boolean setLoadState(Object mapRegion, byte state) {
        if (!initialized || !(mapRegion instanceof MapRegion region)) return false;
        region.setLoadState(state);
        return true;
    }

    public static boolean setShouldCache(Object mapRegion, boolean value) {
        if (!initialized || !(mapRegion instanceof LeveledRegion<?> region)) return false;
        region.setShouldCache(value, "mapsyncer");
        return true;
    }

    public static boolean setHasHadTerrain(Object mapRegion) {
        if (!initialized || !(mapRegion instanceof MapRegion region)) return false;
        region.setHasHadTerrain();
        return true;
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
        String cached = dimNameCache.get(dimId);
        if (cached != null) return cached;
        MapProcessor processor = getMapProcessor();
        if (processor == null) return null;
        ResourceKey<Level> key = toDimensionKey(dimId);
        if (key == null) return null;
        String name = processor.getDimensionName(key);
        if (name != null) {
            dimNameCache.put(dimId, name);
        }
        return name;
    }

    public static ResourceKey<Level> toDimensionKey(String dimId) {
        if (dimId == null || dimId.isEmpty()) {
            return null;
        }
        if ("DIM-1".equals(dimId)) {
            dimId = "minecraft:the_nether";
        } else if ("DIM1".equals(dimId)) {
            dimId = "minecraft:the_end";
        } else if ("null".equals(dimId)) {
            dimId = "minecraft:overworld";
        } else if (dimId.startsWith("minecraft:")) {
            dimId = dimId.substring("minecraft:".length());
        }
        if (dimId.contains("$")) {
            return null;
        }
        String[] parts = dimId.split(":", 2);
        String namespace = parts.length == 2 ? parts[0] : "minecraft";
        String path = parts.length == 2 ? parts[1] : dimId;
        if (path.contains("/") || path.contains("$")) {
            return null;
        }
        try {
            return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(namespace, path));
        } catch (Exception e) {
            LOGGER.warn("Invalid dimension id '{}'", dimId, e);
            return null;
        }
    }

    public static boolean prepareRegionLoad(Object mapRegion) {
        boolean step1 = cancelRefresh(mapRegion);
        boolean step2 = setShouldCache(mapRegion, true);
        boolean step3 = setHasHadTerrain(mapRegion);
        if (step1 && step2 && step3) {
            return true;
        }
        LOGGER.warn(
                "Region load preparation partially failed: cancelRefresh={}, setShouldCache={}, setHasHadTerrain={}",
                step1,
                step2,
                step3);
        return false;
    }

    public static void onWorldIdChanged() {
        MapPacketHandler.onXaeroWorldContextReady();
    }

    public static void reset() {
        cachedSession = null;
        cachedMapProcessor = null;
        cachedMapSaveLoad = null;
        dimNameCache.clear();
        LOGGER.debug("Xaero WorldMap bridge cache reset");
    }
}
