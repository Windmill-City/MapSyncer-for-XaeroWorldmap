package com.mapsyncer.client;

import com.mapsyncer.util.XaeroPathResolver;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XaeroMapIntegrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapIntegrator.class);

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
        return rawIP;
    }

    private static String getCurrentServerIP() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return null;
        }

        Boolean differentiateByServerAddress = XaeroReflectionHelper.getDifferentiateByServerAddress();
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
        String serverIP = getCurrentServerIP();
        if (serverIP == null) {
            return null;
        }

        Minecraft mc = Minecraft.getInstance();
        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = XaeroPathResolver.getWorldMapDir(gameDir);
        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);
        LOGGER.debug("Server directory: {}", serverDir);
        return serverDir;
    }
}
