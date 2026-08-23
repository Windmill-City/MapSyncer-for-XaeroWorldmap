package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.UpdateMode;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateHandler.class);

    private static final UpdateHandler INSTANCE = new UpdateHandler();

    private final AtomicBoolean updateInProgress = new AtomicBoolean(false);

    private volatile @Nullable ExecutorService updateExecutor = null;

    public static UpdateHandler get() {
        return INSTANCE;
    }

    public void onPlayerLoggedOut(MinecraftServer server) {
        if (server.isStopped()) return;

        if (ModConfig.SERVER.incrementalUpdateMode.get() != UpdateMode.ON_EMPTY) return;

        server.execute(() -> checkAndScan(server));
    }

    private void checkAndScan(MinecraftServer server) {
        if (updateInProgress.get()) return;

        if (server.getPlayerList().getPlayerCount() > 0) return;

        performUpdate(server);
    }

    private void performUpdate(MinecraftServer server) {
        if (!updateInProgress.compareAndSet(false, true)) {
            LOGGER.debug("Incremental update already in progress, skipping");
            return;
        }

        LOGGER.info("Performing incremental update: ON_EMPTY mode, no players online");

        try {
            server.saveEverything(false, true, true);
        } catch (RuntimeException e) {
            LOGGER.error("Runtime error saving chunks for incremental scan", e);
            updateInProgress.set(false);
            return;
        }

        getUpdateExecutor().submit(() -> {
            try {
                ConversionOrchestrator.performIncrementalScan(server);
            } catch (RuntimeException e) {
                LOGGER.error("Error during incremental update", e);
            } finally {
                updateInProgress.set(false);
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
        ConversionOrchestrator.requestCancel();
        updateInProgress.set(false);
        shutdownExecutor();
        LOGGER.info("Incremental update handler stopped");
    }

    private void shutdownExecutor() {
        if (updateExecutor != null) {
            updateExecutor.shutdownNow();
            try {
                if (!updateExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("Update executor did not terminate in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            updateExecutor = null;
        }
    }
}
