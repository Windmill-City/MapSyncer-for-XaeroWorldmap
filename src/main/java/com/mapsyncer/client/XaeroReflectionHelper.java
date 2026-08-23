package com.mapsyncer.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class XaeroReflectionHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroReflectionHelper.class);

    public static final byte LOAD_STATE_CLEARED = 4;

    private static volatile boolean initialized = false;

    private static Class<?> worldMapSessionClass;
    private static Class<?> mapProcessorClass;
    private static Class<?> mapSaveLoadClass;
    private static Class<?> mapRegionClass;
    private static Class<?> leveledRegionClass;

    private static Method getCurrentSessionMethod;
    private static Method getMapProcessorMethod;
    private static Method getMapSaveLoadMethod;
    private static Method getLeafMapRegionMethod;
    private static Method requestLoadMethod;
    private static Method cancelRefreshMethod;
    private static Method setHasHadTerrainMethod;
    private static Method getCurrentWorldIdMethod;
    private static Method getDimensionNameMethod;
    private static Method getRootFolderMethod;

    private static Field loadStateField;
    private static Field shouldCacheField;

    private static Object cachedSession;
    private static Object cachedMapProcessor;
    private static Object cachedMapSaveLoad;

    public static boolean initialize() {

        try {
            LOGGER.info("开始初始化 Xaero 反射缓存...");

            LOGGER.debug("加载 Xaero 核心类...");
            worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            mapSaveLoadClass = Class.forName("xaero.map.file.MapSaveLoad");
            mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            leveledRegionClass = Class.forName("xaero.map.region.LeveledRegion");
            LOGGER.info("成功加载 {} 个 Xaero 类", 5);

            LOGGER.debug("获取并缓存反射方法...");
            getCurrentSessionMethod = worldMapSessionClass.getMethod("getCurrentSession");
            getMapProcessorMethod = worldMapSessionClass.getMethod("getMapProcessor");
            getMapSaveLoadMethod = mapProcessorClass.getMethod("getMapSaveLoad");
            getLeafMapRegionMethod =
                    mapProcessorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            requestLoadMethod = mapSaveLoadClass.getMethod("requestLoad", mapRegionClass, String.class, boolean.class);
            cancelRefreshMethod = mapRegionClass.getMethod("cancelRefresh", mapProcessorClass);
            setHasHadTerrainMethod = mapRegionClass.getMethod("setHasHadTerrain");
            getCurrentWorldIdMethod = mapProcessorClass.getMethod("getCurrentWorldId");
            getDimensionNameMethod =
                    mapProcessorClass.getMethod("getDimensionName", net.minecraft.resources.ResourceKey.class);
            getRootFolderMethod = mapSaveLoadClass.getMethod("getRootFolder", String.class);
            LOGGER.info("成功缓存 {} 个反射方法", 10);

            LOGGER.debug("获取并缓存反射字段...");
            loadStateField = mapRegionClass.getDeclaredField("loadState");
            loadStateField.setAccessible(true);
            shouldCacheField = leveledRegionClass.getDeclaredField("shouldCache");
            shouldCacheField.setAccessible(true);
            LOGGER.info("成功缓存 {} 个反射字段", 2);

            initialized = true;
            LOGGER.info("Xaero reflection helper initialized successfully");
            return true;

        } catch (Exception e) {
            LOGGER.error("Unable to initialize Xaero reflection helper:", e);
            return false;
        }
    }

    public static Object getSession() {
        if (!initialized || getCurrentSessionMethod == null) return null;

        try {
            cachedSession = getCurrentSessionMethod.invoke(null);
            return cachedSession;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Failed to get WorldMapSession", e);
            return null;
        }
    }

    public static Object getMapProcessor() {
        if (!initialized || getMapProcessorMethod == null) return null;

        try {
            if (cachedMapProcessor == null) {
                Object session = getSession();
                if (session == null) return null;
                cachedMapProcessor = getMapProcessorMethod.invoke(session);
            }
            return cachedMapProcessor;
        } catch (Exception e) {
            LOGGER.warn("Failed to get MapProcessor", e);
            return null;
        }
    }

    public static Object getMapSaveLoad() {
        if (!initialized || getMapSaveLoadMethod == null) return null;

        try {
            if (cachedMapSaveLoad == null) {
                Object processor = getMapProcessor();
                if (processor == null) return null;
                cachedMapSaveLoad = getMapSaveLoadMethod.invoke(processor);
            }
            return cachedMapSaveLoad;
        } catch (Exception e) {
            LOGGER.warn("Failed to get MapSaveLoad", e);
            return null;
        }
    }

    public static Object getLeafMapRegion(int caveLayer, int regionX, int regionZ, boolean createIfMissing) {
        if (!initialized || getLeafMapRegionMethod == null) return null;

        try {
            Object processor = getMapProcessor();
            if (processor == null) return null;
            return getLeafMapRegionMethod.invoke(processor, caveLayer, regionX, regionZ, createIfMissing);
        } catch (Exception e) {
            LOGGER.warn("Failed to get MapRegion ({}, {}) layer={}", regionX, regionZ, caveLayer, e);
            return null;
        }
    }

    public static boolean requestLoad(Object mapRegion, String reason, boolean prioritize) {
        if (!initialized || requestLoadMethod == null) {
            return false;
        }

        try {
            Object saveLoad = getMapSaveLoad();
            if (saveLoad == null) {
                LOGGER.warn("requestLoad 失败：无法获取 MapSaveLoad 实例");
                return false;
            }
            requestLoadMethod.invoke(saveLoad, mapRegion, reason, prioritize);
            LOGGER.debug("requestLoad 成功执行 (reason={}, prioritize={})", reason, prioritize);
            return true;
        } catch (Exception e) {
            LOGGER.error("requestLoad 反射调用失败 (reason={}): {}", reason, e.getMessage(), e);
            return false;
        }
    }

    public static boolean cancelRefresh(Object mapRegion) {
        if (!initialized || cancelRefreshMethod == null) {
            return false;
        }

        try {
            Object processor = getMapProcessor();
            if (processor == null) {
                LOGGER.warn("cancelRefresh 失败：无法获取 MapProcessor 实例");
                return false;
            }
            cancelRefreshMethod.invoke(mapRegion, processor);
            LOGGER.debug("cancelRefresh 成功执行");
            return true;
        } catch (Exception e) {
            LOGGER.error("cancelRefresh 反射调用失败: {}", e.getMessage(), e);
            return false;
        }
    }

    public static boolean setLoadState(Object mapRegion, byte state) {
        if (!initialized || loadStateField == null) {
            return false;
        }

        try {
            loadStateField.setByte(mapRegion, state);
            LOGGER.debug("setLoadState 成功设置为 {}", state);
            return true;
        } catch (Exception e) {
            LOGGER.error("setLoadState 反射调用失败 (state={}): {}", state, e.getMessage(), e);
            return false;
        }
    }

    public static boolean setShouldCache(Object mapRegion, boolean value) {
        if (!initialized || shouldCacheField == null) {
            return false;
        }

        try {
            shouldCacheField.setBoolean(mapRegion, value);
            LOGGER.debug("setShouldCache 成功设置为 {}", value);
            return true;
        } catch (Exception e) {
            LOGGER.error("setShouldCache 反射调用失败 (value={}): {}", value, e.getMessage(), e);
            return false;
        }
    }

    public static boolean setHasHadTerrain(Object mapRegion) {
        if (!initialized || setHasHadTerrainMethod == null) {
            return false;
        }

        try {
            setHasHadTerrainMethod.invoke(mapRegion);
            LOGGER.debug("setHasHadTerrain 成功执行");
            return true;
        } catch (Exception e) {
            LOGGER.error("setHasHadTerrain 反射调用失败: {}", e.getMessage(), e);
            return false;
        }
    }

    public static String getCurrentWorldId() {
        if (!initialized || getCurrentWorldIdMethod == null) return null;

        try {
            Object processor = getMapProcessor();
            if (processor == null) return null;
            return (String) getCurrentWorldIdMethod.invoke(processor);
        } catch (Exception e) {
            LOGGER.warn("Failed to get current world id", e);
            return null;
        }
    }

    public static Path getCurrentServerDirectory() {
        if (!initialized || getRootFolderMethod == null) return null;

        String worldId = getCurrentWorldId();
        if (worldId == null || worldId.isEmpty()) {
            return null;
        }

        try {
            Object result = getRootFolderMethod.invoke(null, worldId);
            if (result instanceof Path path) {
                return path;
            }
            return null;
        } catch (Exception e) {
            LOGGER.warn("Failed to get current server directory for world id '{}'", worldId, e);
            return null;
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, String> dimNameCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static String getDimensionName(String dimId) {
        if (!initialized || getDimensionNameMethod == null) return null;

        String cached = dimNameCache.get(dimId);
        if (cached != null) {
            return cached;
        }

        try {
            Object processor = getMapProcessor();
            if (processor == null) return null;
            net.minecraft.resources.ResourceKey<?> key = toDimensionKey(dimId);
            if (key == null) return null;
            Object name = getDimensionNameMethod.invoke(processor, key);
            if (name instanceof String s) {
                dimNameCache.put(dimId, s);
                return s;
            }
            return null;
        } catch (Exception e) {
            LOGGER.warn("Failed to get Xaero dimension name for '{}'", dimId, e);
            return null;
        }
    }

    private static net.minecraft.resources.ResourceKey<?> toDimensionKey(String dimId) {
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
            return net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    new net.minecraft.resources.ResourceLocation(namespace, path));
        } catch (Exception e) {
            LOGGER.warn("Invalid dimension id '{}'", dimId, e);
            return null;
        }
    }

    public static boolean prepareRegionLoad(Object mapRegion) {
        LOGGER.debug("开始准备区域加载 (region={})...", mapRegion);

        boolean step1 = cancelRefresh(mapRegion);
        boolean step2 = setShouldCache(mapRegion, true);
        boolean step3 = setHasHadTerrain(mapRegion);

        if (step1 && step2 && step3) {
            LOGGER.debug("区域加载准备完成");
            return true;
        } else {
            LOGGER.warn("区域加载准备部分失败: cancelRefresh={}, setShouldCache={}, setHasHadTerrain={}", step1, step2, step3);
            return false;
        }
    }
}
