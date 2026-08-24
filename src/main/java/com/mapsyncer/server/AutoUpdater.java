package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AutoUpdater {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoUpdater.class);

    private static final AtomicBoolean running = new AtomicBoolean(false);

    public static void onPlayerLoggedOut(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || server.isStopped()) {
            LOGGER.debug("Skipping: server is null or already stopped");
            return;
        }

        boolean enabled = ModConfig.SERVER.config().automaticUpdateEnabled.get();
        if (!enabled) {
            LOGGER.debug("Skipping: automatic update is disabled");
            return;
        }

        checkAndScan(server);
    }

    private static void checkAndScan(MinecraftServer server) {
        if (running.get()) {
            LOGGER.debug("Skipping: an automatic update is already in progress");
            return;
        }

        int playerCount = server.getPlayerList().getPlayerCount();
        if (playerCount > 1) {
            LOGGER.debug("Skipping: {} players are still online", playerCount);
            return;
        }

        performUpdate(server);
    }

    public static void performUpdate(MinecraftServer server) {
        if (!running.compareAndSet(false, true)) {
            LOGGER.debug("Skipping: automatic update is already in progress");
            return;
        }

        LOGGER.info("Starting automatic map update (no players online)");

        Util.ioPool().execute(() -> {
            try {
                MapConverter.performAutomaticScan(server);
            } catch (RuntimeException e) {
                LOGGER.error("Automatic map update failed", e);
            } finally {
                running.set(false);
            }
        });
    }

    public static void stop() {
        MapConverter.requestCancel();
        running.set(false);
        LOGGER.info("Automatic updater stopped");
    }
}
