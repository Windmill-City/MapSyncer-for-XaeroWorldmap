package com.mapsyncer.client;

import com.mapsyncer.util.XaeroPathResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class XaeroMapIntegrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapIntegrator.class);

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
        Path worldMapDir = XaeroPathResolver.getWorldMapDir(gameDir);

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
        Path worldMapDir = XaeroPathResolver.getWorldMapDir(gameDir);
        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);
        LOGGER.debug("Server directory: {}", serverDir);
        return serverDir;
    }

    public static Path getClientXaeroWorldMapDir() {
        try {
            Path serverDir = getCurrentServerDirectory();
            if (serverDir != null) {
                return serverDir;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc.gameDirectory != null) {
                return XaeroPathResolver.getWorldMapDir(mc.gameDirectory.toPath());
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to get Xaero world map dir: {}", e.getMessage());
        }
        return null;
    }
}
