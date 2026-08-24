package com.mapsyncer.server;

import com.mapsyncer.MapSyncer;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AutoUpdater {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoUpdater.class);

    public static void onPlayerLoggedOut(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || server.isStopped()) {
            LOGGER.debug("Skipping: server is null or already stopped");
            return;
        }

        boolean enabled = MapSyncer.isAutoUpdate();
        if (!enabled) {
            LOGGER.debug("Skipping: autoupdate is disabled");
            return;
        }

        checkAndScan(server);
    }

    private static void checkAndScan(MinecraftServer server) {
        int playerCount = server.getPlayerList().getPlayerCount();
        if (playerCount > 1) {
            LOGGER.debug("Skipping: {} players are still online", playerCount);
            return;
        }

        performScan(server);
    }

    public static void performScan(MinecraftServer server) {
        LOGGER.info("Starting map autoupdate (no players online)");

        Util.ioPool().execute(() -> {
            MapConverter.generate(server);
        });
    }

    public static void stop() {
        MapConverter.requestCancel();
        LOGGER.info("Stopping map autoupdate");
    }
}
