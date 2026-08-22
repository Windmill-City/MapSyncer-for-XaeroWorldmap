package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.UpdateMode;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod.EventBusSubscriber(modid = "mapsyncer", value = {Dist.CLIENT, Dist.DEDICATED_SERVER}, bus = Mod.EventBusSubscriber.Bus.FORGE)
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

    private volatile LocalDateTime lastScheduledUpdate = null;

    private volatile ExecutorService updateExecutor = null;

    private final AtomicBoolean updateInProgress = new AtomicBoolean(false);

    private IncrementalUpdateHandlerLogic() {

    }

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
        this.lastScheduledUpdate = null;

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        if (mode == UpdateMode.SCHEDULED) {
            LOGGER.info("Incremental update handler started (SCHEDULED mode, daily at {}:{})",
                ModConfig.SERVER.scheduledUpdateHour.get(),
                ModConfig.SERVER.scheduledUpdateMinute.get());
        }
    }

    public void stop() {
        running = false;
        updateInProgress.set(false);
        shutdownExecutor();
        server = null;
        lastScheduledUpdate = null;
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

    public boolean isRunning() {
        return running;
    }

    public void onServerTick() {
        if (!running || server == null) return;

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        if (mode == UpdateMode.DISABLED) return;

        if (mode == UpdateMode.SCHEDULED) {
            checkScheduledMode();
        }
    }

    private void checkScheduledMode() {
        LocalDateTime now = LocalDateTime.now();
        int targetHour = ModConfig.SERVER.scheduledUpdateHour.get();
        int targetMinute = ModConfig.SERVER.scheduledUpdateMinute.get();
        LocalTime targetTime = LocalTime.of(targetHour, targetMinute);
        LocalTime currentTime = now.toLocalTime();

        if (currentTime.isAfter(targetTime) && currentTime.isBefore(targetTime.plusMinutes(1))) {
            if (lastScheduledUpdate == null || !lastScheduledUpdate.toLocalDate().equals(now.toLocalDate())) {
                lastScheduledUpdate = now;
                performScheduledUpdate("SCHEDULED mode daily update at " + targetHour + ":" + targetMinute);
            }
        }
    }

    private void performScheduledUpdate(String reason) {
        if (!updateInProgress.compareAndSet(false, true)) {
            LOGGER.debug("Scheduled update already in progress, skipping");
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
                LOGGER.error("Error during scheduled incremental update", e);
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
