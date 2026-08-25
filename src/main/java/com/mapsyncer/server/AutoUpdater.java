package com.mapsyncer.server;

import com.mapsyncer.MapSyncer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class AutoUpdater {

    private static final Logger LOGGER = LogManager.getLogger(AutoUpdater.class);

    private static final AtomicBoolean running = new AtomicBoolean(false);

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

    public static void performScan(MinecraftServer server) {
        if (!running.compareAndSet(false, true)) {
            LOGGER.debug("Skipping: autoupdater is already running");
            return;
        }
        LOGGER.info("Starting map autoupdate (no players online)");

        server.saveAllChunks(true, true, false);

        Util.ioPool().execute(() -> {
            try {
                MapConverter.start(server);
            } finally {
                running.set(false);
            }
        });
    }

    public static void stop() {
        if (!running.compareAndSet(true, false)) {
            LOGGER.debug("Skipping: autoupdater is not running");
            return;
        }
        MapConverter.stop();
        LOGGER.info("Stopping map autoupdate");
    }

    private static void checkAndScan(MinecraftServer server) {
        int playerCount = server.getPlayerList().getPlayerCount();
        if (playerCount > 1) {
            LOGGER.debug("Skipping: {} players are still online", playerCount);
            return;
        }

        performScan(server);
    }
}
