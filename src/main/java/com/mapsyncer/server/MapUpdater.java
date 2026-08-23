package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.UpdateMode;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapUpdater {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapUpdater.class);

    private static final MapUpdater INSTANCE = new MapUpdater();

    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile ExecutorService updateExecutor = null;

    public static MapUpdater get() {
        return INSTANCE;
    }

    public void onPlayerLoggedOut(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || server.isStopped()) {
            LOGGER.debug("[onPlayerLoggedOut] ignoring: server is null or stopped");
            return;
        }

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        if (mode != UpdateMode.ON_EMPTY) {
            LOGGER.debug("[onPlayerLoggedOut] ignoring: update mode is not ON_EMPTY");
            return;
        }

        checkAndScan(server);
    }

    private void checkAndScan(MinecraftServer server) {
        if (running.get()) {
            LOGGER.debug("[onPlayerLoggedOut] ignoring: already running");
            return;
        }

        int playerCount = server.getPlayerList().getPlayerCount();
        if (playerCount > 1) {

            LOGGER.debug("[onPlayerLoggedOut] ignoring: player count > 1");
            return;
        }

        performUpdate(server);
    }

    public void performUpdate(MinecraftServer server) {
        if (!running.compareAndSet(false, true)) {
            LOGGER.debug("Incremental update already in progress, skipping");
            return;
        }

        LOGGER.info("Performing incremental update: ON_EMPTY mode, no players online");

        getUpdateExecutor().submit(() -> {
            try {
                MapConverter.performIncrementalScan(server);
            } catch (RuntimeException e) {
                LOGGER.error("Error during incremental update", e);
            } finally {
                running.set(false);
            }
        });
    }

    private ExecutorService getUpdateExecutor() {
        ExecutorService current = updateExecutor;
        if (current == null) {
            synchronized (this) {
                current = updateExecutor;
                if (current == null) {
                    current = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "mapsyncer-incremental-update");
                        t.setPriority(Thread.MIN_PRIORITY);
                        t.setDaemon(true);
                        return t;
                    });
                    updateExecutor = current;
                }
            }
        }
        return current;
    }

    public void stop() {
        MapConverter.requestCancel();
        running.set(false);
        LOGGER.info("MapUpdater stopped");
    }
}
