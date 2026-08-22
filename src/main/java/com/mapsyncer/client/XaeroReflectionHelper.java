package com.mapsyncer.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
    private static Method setRegionDetectionCompleteMethod;

    private static Field loadStateField;
    private static Field shouldCacheField;
    private static Field worldIdField;
    private static Field dimIdField;
    private static Field mwIdField;

    private static Object cachedSession;
    private static Object cachedMapProcessor;
    private static Object cachedMapSaveLoad;

    private XaeroReflectionHelper() {}

    public static boolean initialize() {
        if (initialized) return true;

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
            getLeafMapRegionMethod = mapProcessorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            requestLoadMethod = mapSaveLoadClass.getMethod("requestLoad", mapRegionClass, String.class, boolean.class);
            cancelRefreshMethod = mapRegionClass.getMethod("cancelRefresh", mapProcessorClass);
            setHasHadTerrainMethod = mapRegionClass.getMethod("setHasHadTerrain");
            setRegionDetectionCompleteMethod = mapSaveLoadClass.getMethod("setRegionDetectionComplete", boolean.class);
            LOGGER.info("成功缓存 {} 个反射方法", 8);

            LOGGER.debug("获取并缓存反射字段...");
            loadStateField = mapRegionClass.getDeclaredField("loadState");
            loadStateField.setAccessible(true);
            shouldCacheField = leveledRegionClass.getDeclaredField("shouldCache");
            shouldCacheField.setAccessible(true);
            worldIdField = leveledRegionClass.getDeclaredField("worldId");
            worldIdField.setAccessible(true);
            dimIdField = leveledRegionClass.getDeclaredField("dimId");
            dimIdField.setAccessible(true);
            mwIdField = leveledRegionClass.getDeclaredField("mwId");
            mwIdField.setAccessible(true);
            LOGGER.info("成功缓存 {} 个反射字段", 5);

            initialized = true;
            LOGGER.info("Xaero reflection helper initialized successfully");
            return true;

        } catch (ClassNotFoundException e) {
            LOGGER.error("Xaero's World Map 未找到或类名不匹配，反射功能禁用", e);
            LOGGER.error("请确保已安装 Xaero's World Map 模组");
            return false;
        } catch (NoSuchMethodException e) {
            LOGGER.error("Xaero API 不兼容，方法签名变化", e);
            LOGGER.error("可能原因：Xaero 版本过新或过旧，与当前 MapSyncer 版本不兼容");
            return false;
        } catch (NoSuchFieldException e) {
            LOGGER.error("Xaero API 不兼容，字段不存在", e);
            LOGGER.error("可能原因：Xaero 版本过新或过旧，与当前 MapSyncer 版本不兼容");
            return false;
        } catch (Exception e) {
            LOGGER.error("❌ 初始化 Xaero reflection helper 失败", e);
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
        if (!initialized) return null;

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
        if (!initialized) return null;

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

    public static boolean setRegionDetectionComplete(boolean value) {
        if (!initialized || setRegionDetectionCompleteMethod == null) {
            LOGGER.warn("setRegionDetectionComplete 失败：反射未初始化或方法缓存为空 (initialized={}, method={})",
                initialized, setRegionDetectionCompleteMethod != null);
            return false;
        }

        try {
            Object saveLoad = getMapSaveLoad();
            if (saveLoad == null) {
                LOGGER.warn("setRegionDetectionComplete 失败：无法获取 MapSaveLoad 实例");
                return false;
            }
            setRegionDetectionCompleteMethod.invoke(saveLoad, value);
            LOGGER.debug("setRegionDetectionComplete 设置为 {}", value);
            return true;
        } catch (Exception e) {
            LOGGER.error("setRegionDetectionComplete 反射调用失败 (value={}): {}", value, e.getMessage(), e);
            return false;
        }
    }

    public static boolean requestLoad(Object mapRegion, String reason, boolean prioritize) {
        if (!initialized || requestLoadMethod == null) {
            LOGGER.warn("requestLoad 失败：反射未初始化或方法缓存为空 (initialized={}, method={})",
                initialized, requestLoadMethod != null);
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
            LOGGER.warn("cancelRefresh 失败：反射未初始化或方法缓存为空 (initialized={}, method={})",
                initialized, cancelRefreshMethod != null);
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
            LOGGER.warn("setLoadState 失败：反射未初始化或字段缓存为空 (initialized={}, field={})",
                initialized, loadStateField != null);
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
            LOGGER.warn("setShouldCache 失败：反射未初始化或字段缓存为空 (initialized={}, field={})",
                initialized, shouldCacheField != null);
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
            LOGGER.warn("setHasHadTerrain 失败：反射未初始化或方法缓存为空 (initialized={}, method={})",
                initialized, setHasHadTerrainMethod != null);
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

    public static String getWorldId(Object mapRegion) {
        if (!initialized || worldIdField == null) return null;

        try {
            return (String) worldIdField.get(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get worldId", e);
            return null;
        }
    }

    public static String getDimId(Object mapRegion) {
        if (!initialized || dimIdField == null) return null;

        try {
            return (String) dimIdField.get(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get dimId", e);
            return null;
        }
    }

    public static String getMwId(Object mapRegion) {
        if (!initialized || mwIdField == null) return null;

        try {
            return (String) mwIdField.get(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get mwId", e);
            return null;
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void clearCache() {
        initialized = false;
        cachedSession = null;
        cachedMapProcessor = null;
        cachedMapSaveLoad = null;
        LOGGER.info("Xaero reflection cache cleared");
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
            LOGGER.warn("区域加载准备部分失败: cancelRefresh={}, setShouldCache={}, setHasHadTerrain={}",
                step1, step2, step3);
            return false;
        }
    }
}
