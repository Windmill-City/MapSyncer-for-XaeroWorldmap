package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.UpdateMode;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod.EventBusSubscriber(
        modid = "mapsyncer",
        value = {Dist.CLIENT, Dist.DEDICATED_SERVER},
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public class IncrementalUpdateHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncrementalUpdateHandlerLogic.class);

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        getInstance().onServerTick();
    }

    private static volatile IncrementalUpdateHandlerLogic instance;

    private volatile MinecraftServer server;

    private volatile boolean running = false;

    private volatile ExecutorService updateExecutor = null;

    private final AtomicBoolean updateInProgress = new AtomicBoolean(false);

    private volatile boolean emptyModeTriggered = false;

    private IncrementalUpdateHandlerLogic() {}

    public static IncrementalUpdateHandlerLogic getInstance() {
        if (instance == null) {
            synchronized (IncrementalUpdateHandlerLogic.class) {
                if (instance == null) {
                    instance = new IncrementalUpdateHandlerLogic();
                }
            }
        }
        return instance;
    }

    public void start(MinecraftServer server) {
        if (running) {
            LOGGER.warn("Incremental update handler already running");
            return;
        }
        this.server = server;
        this.running = true;
        this.emptyModeTriggered = false;

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        if (mode == UpdateMode.ON_EMPTY) {
            LOGGER.info("Incremental update handler started (ON_EMPTY mode, runs when no players are online)");
        }
    }

    public void stop() {
        running = false;
        updateInProgress.set(false);
        shutdownExecutor();
        server = null;
        emptyModeTriggered = false;
        LOGGER.info("Incremental update handler stopped");
    }

    private ExecutorService getUpdateExecutor() {
        if (updateExecutor == null) {
            synchronized (this) {
                if (updateExecutor == null) {
                    updateExecutor = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "mapsyncer-incremental-update");
                        t.setPriority(Thread.MIN_PRIORITY);
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return updateExecutor;
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

    public void onServerTick() {
        if (!running || server == null) return;

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        if (mode == UpdateMode.DISABLED) return;

        if (mode == UpdateMode.ON_EMPTY) {
            checkEmptyMode();
        }
    }

    private void checkEmptyMode() {
        if (updateInProgress.get()) return;

        int playerCount = server.getPlayerList().getPlayerCount();
        if (playerCount > 0) {
            emptyModeTriggered = false;
            return;
        }

        if (emptyModeTriggered) return;

        emptyModeTriggered = true;
        performUpdate("ON_EMPTY mode: no players online");
    }

    private void performUpdate(String reason) {
        if (!updateInProgress.compareAndSet(false, true)) {
            LOGGER.debug("Incremental update already in progress, skipping");
            return;
        }

        LOGGER.info("Performing incremental update: {}", reason);

        try {
            server.saveEverything(false, true, true);
        } catch (RuntimeException e) {
            LOGGER.error("Runtime error saving chunks for incremental scan", e);
            updateInProgress.set(false);
            return;
        }

        final MinecraftServer currentServer = this.server;
        getUpdateExecutor().submit(() -> {
            try {
                ConversionOrchestrator.performIncrementalScan(currentServer);
            } catch (RuntimeException e) {
                LOGGER.error("Error during incremental update", e);
            } finally {
                updateInProgress.set(false);
            }

            if (currentServer.getPlayerList().getPlayerCount() == 0) {
                LOGGER.info("No players online after incremental update, stopping handler to save resources");
                currentServer.execute(IncrementalUpdateHandlerLogic.this::stop);
            }
        });
    }

    public static void resetInstance() {
        if (instance != null) {
            instance.stop();
            instance = null;
            LOGGER.info("IncrementalUpdateHandlerLogic instance reset");
        }
    }
}
