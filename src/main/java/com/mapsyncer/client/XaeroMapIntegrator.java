package com.mapsyncer.client;

import com.mapsyncer.util.XaeroPathResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XaeroMapIntegrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapIntegrator.class);

    private static final String OPTION_DIFFERENTIATE_BY_SERVER_ADDRESS = "differentiate_by_server_address";
    private static final String FALLBACK_ANY_ADDRESS = "Any Address";

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

    private static Boolean getDifferentiateByServerAddress() {
        Minecraft mc = Minecraft.getInstance();
        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = XaeroPathResolver.getWorldMapDir(gameDir);
        Path[] candidates = {
            gameDir.resolve("config").resolve("xaeroworldmap-options.txt"), worldMapDir.resolve("options.txt"),
        };
        for (Path candidate : candidates) {
            Boolean value = readDifferentiateByServerAddress(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Boolean readDifferentiateByServerAddress(Path optionsFile) {
        if (!Files.isRegularFile(optionsFile)) {
            return null;
        }
        try (Stream<String> lines = Files.lines(optionsFile, StandardCharsets.UTF_8)) {
            return lines.map(line -> {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            return null;
                        }
                        String[] parts = line.split(":", 2);
                        if (parts.length != 2 || !parts[0].endsWith(OPTION_DIFFERENTIATE_BY_SERVER_ADDRESS)) {
                            return null;
                        }
                        String raw = parts[1].trim();
                        if ("true".equalsIgnoreCase(raw)) {
                            return Boolean.TRUE;
                        }
                        if ("false".equalsIgnoreCase(raw)) {
                            return Boolean.FALSE;
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            LOGGER.warn("Failed to read Xaero options file: {}", optionsFile, e);
            return null;
        }
    }

    private static String getCurrentServerIP() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return null;
        }

        if (mc.hasSingleplayerServer()) {
            return "Singleplayer";
        }

        Boolean differentiateByServerAddress = getDifferentiateByServerAddress();
        if (Boolean.FALSE.equals(differentiateByServerAddress)) {
            LOGGER.debug(
                    "Xaero differentiate_by_server_address is false, using '{}' server folder", FALLBACK_ANY_ADDRESS);
            return FALLBACK_ANY_ADDRESS;
        }

        ServerData serverData = connection.getServerData();
        if (serverData != null && serverData.ip != null && !serverData.ip.isEmpty()) {
            return cleanServerIP(serverData.ip);
        }

        return "Unknown";
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
}
