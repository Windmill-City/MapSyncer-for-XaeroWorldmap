package com.mapsyncer.server;

import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class IncrementalUpdateHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncrementalUpdateHandlerLogic.class);

    private static volatile IncrementalUpdateHandlerLogic instance;

    private volatile MinecraftServer server;

    private volatile boolean running = false;

    private final AtomicInteger tickCounter = new AtomicInteger(0);

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
        this.tickCounter.set(0);
        this.lastScheduledUpdate = null;

        UpdateMode mode = PlatformManager.getPlatform().getIncrementalUpdateMode();
        if (mode == UpdateMode.TICK) {
            LOGGER.info("Incremental update handler started (TICK mode, interval: {} ticks = {} seconds)",
                PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks(),
                PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks() / 20);
        } else if (mode == UpdateMode.SCHEDULED) {
            LOGGER.info("Incremental update handler started (SCHEDULED mode, daily at {}:{})",
                PlatformManager.getPlatform().getScheduledUpdateHour(),
                PlatformManager.getPlatform().getScheduledUpdateMinute());
        }
    }

    public void stop() {
        running = false;
        updateInProgress.set(false);
        shutdownExecutor();
        server = null;
        tickCounter.set(0);
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

    public int getTickCounter() {
        return tickCounter.get();
    }

    public void onServerTick() {
        if (!running || server == null) return;

        UpdateMode mode = PlatformManager.getPlatform().getIncrementalUpdateMode();
        if (mode == UpdateMode.DISABLED) return;

        switch (mode) {
            case TICK:
                checkTickMode();
                break;
            case SCHEDULED:
                checkScheduledMode();
                break;
            case DISABLED:

                break;
        }
    }

    private void checkTickMode() {
        int interval = PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks();
        int currentTick = tickCounter.incrementAndGet();

        if (currentTick >= interval) {
            tickCounter.set(0);
            performScheduledUpdate("TICK mode interval");
        }
    }

    private void checkScheduledMode() {
        LocalDateTime now = LocalDateTime.now();
        int targetHour = PlatformManager.getPlatform().getScheduledUpdateHour();
        int targetMinute = PlatformManager.getPlatform().getScheduledUpdateMinute();
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

    public String getStatusInfo() {
        if (!running) {
            return "Stopped";
        }

        UpdateMode mode = PlatformManager.getPlatform().getIncrementalUpdateMode();
        switch (mode) {
            case DISABLED:
                return "Running but disabled";
            case TICK:
                int interval = PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks();
                int remaining = interval - tickCounter.get();
                return String.format("TICK mode: next update in %d ticks (%.1f seconds)",
                    remaining, remaining / 20.0f);
            case SCHEDULED:
                int targetHour = PlatformManager.getPlatform().getScheduledUpdateHour();
                int targetMinute = PlatformManager.getPlatform().getScheduledUpdateMinute();
                LocalDateTime now = LocalDateTime.now();
                LocalTime targetTime = LocalTime.of(targetHour, targetMinute);
                LocalDateTime nextUpdate = now.toLocalDate().atTime(targetTime);
                if (now.toLocalTime().isAfter(targetTime)) {
                    nextUpdate = nextUpdate.plusDays(1);
                }
                long secondsUntil = java.time.Duration.between(now, nextUpdate).getSeconds();
                return String.format("SCHEDULED mode: next update at %02d:%02d (in %dh %dm)",
                    targetHour, targetMinute, secondsUntil / 3600, (secondsUntil % 3600) / 60);
            default:
                return "Unknown mode";
        }
    }

    public static void resetInstance() {
        if (instance != null) {
            instance.stop();
            instance = null;
            LOGGER.info("IncrementalUpdateHandlerLogic instance reset");
        }
    }
}
