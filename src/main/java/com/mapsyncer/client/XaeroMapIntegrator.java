package com.mapsyncer.client;

import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.platform.XaeroReflectionHelper;
import com.mapsyncer.util.XaeroPathResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class XaeroMapIntegrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapIntegrator.class);

    public static Path getWorldMapDir(Path gameDir) {
        return XaeroPathResolver.getWorldMapDir(gameDir);
    }

    public static Set<XaeroMapDataHandler.RegionCoord> getViewDistanceRegions() {
        return getViewDistanceRegions(Integer.MAX_VALUE);
    }

    public static Set<XaeroMapDataHandler.RegionCoord> getViewDistanceRegions(int caveLayer) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            return new HashSet<>();
        }

        int playerChunkX = player.getBlockX() >> 4;
        int playerChunkZ = player.getBlockZ() >> 4;
        int viewDistance = mc.options.renderDistance().get();

        int minChunkX = playerChunkX - viewDistance;
        int maxChunkX = playerChunkX + viewDistance;
        int minChunkZ = playerChunkZ - viewDistance;
        int maxChunkZ = playerChunkZ + viewDistance;

        int minRegionX = minChunkX >> 5;
        int maxRegionX = maxChunkX >> 5;
        int minRegionZ = minChunkZ >> 5;
        int maxRegionZ = maxChunkZ >> 5;

        Set<XaeroMapDataHandler.RegionCoord> viewRegions = new HashSet<>();
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                viewRegions.add(new XaeroMapDataHandler.RegionCoord(rx, rz, caveLayer));
            }
        }

        LOGGER.debug("View distance regions: viewDistance={}, chunks ({},{}) to ({},{}), regions ({},{}) to ({},{}), total {} (layer={})",
                viewDistance, minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                minRegionX, minRegionZ, maxRegionX, maxRegionZ, viewRegions.size(), caveLayer);

        return viewRegions;
    }

    public static int unloadViewDistanceRegions() {
        Set<XaeroMapDataHandler.RegionCoord> viewRegions = getViewDistanceRegions();
        if (viewRegions.isEmpty()) {
            LOGGER.info("No view distance regions to unload");
            return 0;
        }

        LOGGER.info("Unloading {} view distance regions before sync", viewRegions.size());
        return resetSpecificRegionLoadStates(viewRegions);
    }

    public static int resetSpecificRegionLoadStates(Set<XaeroMapDataHandler.RegionCoord> regionsToReset) {
        int resetCount = 0;

        if (!XaeroReflectionHelper.isInitialized()) {
            LOGGER.warn("XaeroReflectionHelper not initialized for selective reset");
            return 0;
        }

        java.util.Map<Integer, Set<XaeroMapDataHandler.RegionCoord>> byLayer = new java.util.HashMap<>();
        for (XaeroMapDataHandler.RegionCoord coord : regionsToReset) {
            byLayer.computeIfAbsent(coord.caveLayer(), k -> new java.util.HashSet<>()).add(coord);
        }

        try {
            for (var entry : byLayer.entrySet()) {
                int caveLayer = entry.getKey();
                Set<XaeroMapDataHandler.RegionCoord> layerTargets = entry.getValue();
                Object regionTextureMap = XaeroReflectionHelper.getRegionTextureMap(caveLayer);
                if (regionTextureMap == null) {
                    LOGGER.warn("Could not get regionTextureMap for layer {}", caveLayer);
                    continue;
                }

                if (regionTextureMap instanceof Map<?, ?> map) {
                    for (Object columnEntry : map.values()) {
                        if (columnEntry instanceof Map<?, ?> column) {
                            for (Object regionEntry : column.values()) {
                                resetCount += selectiveResetLeafRegions(regionEntry, layerTargets, caveLayer);
                            }
                        }
                    }
                }
            }

            LOGGER.info("Selective reset completed: {} regions reset", resetCount);

        } catch (Exception e) {
            LOGGER.warn("Failed to selective reset regions: {}", e.getMessage());
        }

        return resetCount;
    }

    private static int selectiveResetLeafRegions(Object region, Set<XaeroMapDataHandler.RegionCoord> regionsToReset, int caveLayer) {
        int count = 0;
        try {
            if (XaeroReflectionHelper.isMapRegion(region)) {
                int rx = XaeroReflectionHelper.getRegionX(region);
                int rz = XaeroReflectionHelper.getRegionZ(region);

                if (rx == -1 || rz == -1) {
                    LOGGER.warn("无法获取区域坐标 (region={})", region);
                    return 0;
                }

                XaeroMapDataHandler.RegionCoord coord = new XaeroMapDataHandler.RegionCoord(rx, rz, caveLayer);

                if (regionsToReset.contains(coord)) {
                    byte currentLoadState = XaeroReflectionHelper.getLoadState(region);

                    if (currentLoadState == -1) {
                        LOGGER.warn("无法获取区域 ({}, {}) 的 loadState", rx, rz);
                        return 0;
                    }

                    if (currentLoadState == XaeroReflectionHelper.LOAD_STATE_LOADED) {
                        XaeroMapDataHandler.getPreUnloadedRegionsInternal().add(coord);

                        boolean success = XaeroReflectionHelper.setLoadState(region, XaeroReflectionHelper.LOAD_STATE_UNLOADED);
                        if (success) {
                            count++;
                            LOGGER.debug("Pre-unloaded region ({}, {}) layer={} was loaded, recorded for loadState=4", rx, rz, caveLayer);
                        } else {
                            LOGGER.warn("设置区域 ({}, {}) loadState 失败", rx, rz);
                        }
                    } else if (currentLoadState == XaeroReflectionHelper.LOAD_STATE_CLEARED) {
                        XaeroMapDataHandler.getPreUnloadedRegionsInternal().add(coord);
                        boolean success = XaeroReflectionHelper.setLoadState(region, XaeroReflectionHelper.LOAD_STATE_UNLOADED);
                        if (success) {
                            count++;
                        }
                    }
                }
            } else if (XaeroReflectionHelper.isBranchLeveledRegion(region)) {
                Object childrenArray = XaeroReflectionHelper.getBranchChildren(region);

                if (childrenArray != null && childrenArray.getClass().isArray()) {
                    int outerLength = Array.getLength(childrenArray);
                    for (int i = 0; i < outerLength; i++) {
                        Object innerArray = Array.get(childrenArray, i);
                        if (innerArray != null && innerArray.getClass().isArray()) {
                            int innerLength = Array.getLength(innerArray);
                            for (int j = 0; j < innerLength; j++) {
                                Object child = Array.get(innerArray, j);
                                if (child != null) {
                                    count += selectiveResetLeafRegions(child, regionsToReset, caveLayer);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error in selective reset: {}", e.getMessage(), e);
        }
        return count;
    }

    private static String cleanServerIP(String rawIP) {
        int portDivider = rawIP.lastIndexOf(":");
        if (portDivider > 0 && rawIP.indexOf(":") != rawIP.lastIndexOf(":")) {
            portDivider = rawIP.lastIndexOf("]:") + 1;
        }
        if (portDivider > 0) {
            rawIP = rawIP.substring(0, portDivider);
        }
        rawIP = rawIP.replace("[", "").replace("]", "");
        rawIP = rawIP.replaceAll(":", ".");
        while (rawIP.endsWith(".")) {
            rawIP = rawIP.substring(0, rawIP.length() - 1);
        }
        if (rawIP.isEmpty()) {
            rawIP = "Empty Address";
        }
        return rawIP;
    }

    private static String getCurrentServerIP() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return null;
        }

        ServerData serverData = connection.getServerData();
        if (serverData != null && serverData.ip != null && !serverData.ip.isEmpty()) {
            return cleanServerIP(serverData.ip);
        }

        if (mc.hasSingleplayerServer()) {
            return "Singleplayer";
        }
        return "LAN";
    }

    public static Path getCurrentServerBaseDirectory() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        LOGGER.debug("getCurrentServerBaseDirectory: connection={}", connection);
        if (connection == null) {
            LOGGER.warn("getCurrentServerBaseDirectory: connection is null");
            return null;
        }

        ServerData serverData = connection.getServerData();
        LOGGER.debug("getCurrentServerBaseDirectory: serverData={}, serverData.ip={}",
                serverData, serverData != null ? serverData.ip : "N/A");

        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = getWorldMapDir(gameDir);

        String serverIP = getCurrentServerIP();
        if (serverIP == null) {
            return null;
        }

        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);
        Path dimDir = serverDir.resolve("null");

        if (!dimDir.toFile().exists()) {
            try {
                if (worldMapDir.toFile().exists() && worldMapDir.toFile().isDirectory()) {
                    try (var stream = Files.list(worldMapDir)) {
                        stream.filter(p -> p.getFileName().toString().startsWith("Multiplayer_"))
                            .filter(p -> Files.isDirectory(p))
                            .forEach(p -> {
                                Path candidateDim = p.resolve("null");
                                if (candidateDim.toFile().exists()) {
                                    LOGGER.debug("Found existing Xaero directory: {}", candidateDim);
                                }
                            });
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("Failed to scan world-map directory: {}", e.getMessage());
            }

            if (serverIP.equals("Singleplayer") || serverIP.equals("LAN")) {
                LOGGER.info("Creating Xaero directory for {} mode: {}", serverIP, dimDir);
                try {
                    Files.createDirectories(dimDir);
                } catch (IOException e) {
                    LOGGER.warn("Failed to create Xaero directory: {}", e.getMessage());
                }
            }
        }

        LOGGER.debug("Server base directory: {}", dimDir);
        return dimDir;
    }

    public static Path getCurrentServerDirectory() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            LOGGER.warn("getCurrentServerDirectory: connection is null");
            return null;
        }

        String serverIP = getCurrentServerIP();
        if (serverIP == null) {
            return null;
        }

        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = getWorldMapDir(gameDir);
        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);
        LOGGER.debug("Server directory: {}", serverDir);
        return serverDir;
    }

    public static Path writeMapDataAndReturnDir(List<ChunkMapData> chunks, int serverWorldId) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            LOGGER.error("Not connected to server");
            return null;
        }

        ServerData serverData = connection.getServerData();
        if (serverData == null) {
            LOGGER.error("No server data available");
            return null;
        }

        String serverIP = getCurrentServerIP();
        if (serverIP == null) {
            return null;
        }

        LOGGER.info("Using server worldId: {}", serverWorldId);

        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = getWorldMapDir(gameDir);
        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);

        return XaeroMapDataHandler.writeMapData(chunks, serverDir, serverWorldId);
    }

    public static XaeroMapDataHandler.RegionWriteResult writeChunkDataResult(ChunkMapData chunk, int worldId) {
        Path serverDir = getCurrentServerDirectory();
        if (serverDir == null) {
            LOGGER.warn("无法获取服务器目录");
            return null;
        }
        return XaeroMapDataHandler.writeChunkData(chunk, serverDir, worldId);
    }

    public static Path writeChunkDataAndGetMwDir(ChunkMapData chunk, int worldId) {
        XaeroMapDataHandler.RegionWriteResult result = writeChunkDataResult(chunk, worldId);
        return result != null ? result.mwDir() : null;
    }
}
