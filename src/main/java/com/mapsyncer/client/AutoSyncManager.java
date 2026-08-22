package com.mapsyncer.client;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.UpdateMode;
import com.mapsyncer.util.ClientMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AutoSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoSyncManager.class);

    private static final ScheduledExecutorService EXECUTOR =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MapSyncer-AutoSync");
            t.setDaemon(true);
            return t;
        });

    private static volatile long lastAutoSyncTimeMs = 0;
    private static volatile ScheduledFuture<?> pendingTask;
    private static volatile boolean active = false;

    private static volatile int serverAutoSyncIntervalMinutes = -1;
    private static volatile UpdateMode serverUpdateMode = UpdateMode.DISABLED;

    public static Object[] getStatusKey(int intervalMinutes) {
        try {
            if (!ModConfig.CLIENT.isAutoSyncEnabled()) {
                return new Object[]{"mapsyncer.autosync.status.client_disabled"};
            }
        } catch (IllegalStateException ignored) {

        }
        if (intervalMinutes <= 0) return new Object[]{"mapsyncer.autosync.status.disabled"};
        if (intervalMinutes < 1440) return new Object[]{"mapsyncer.autosync.status.minutes", intervalMinutes};
        return new Object[]{"mapsyncer.autosync.status.daily"};
    }

    public static void configureFromServer(UpdateMode mode, int intervalMinutes) {
        serverUpdateMode = mode;
        serverAutoSyncIntervalMinutes = intervalMinutes;
    }

    public static void resetServerPolicy() {
        serverAutoSyncIntervalMinutes = -1;
        serverUpdateMode = UpdateMode.DISABLED;
    }

    public static boolean isServerPolicyKnown() {
        return serverAutoSyncIntervalMinutes >= 0;
    }

    public static boolean isJoinAutoSyncEnabled() {
        if (!ModConfig.CLIENT.isAutoSyncEnabled()) {
            return false;
        }
        return serverAutoSyncIntervalMinutes > 0;
    }

    public static boolean shouldSyncScheduledOnJoin(long serverGenTime) {
        if (serverGenTime <= 0) {
            LOGGER.debug("Scheduled join auto-sync skipped: server has no generation data");
            return false;
        }
        long clientLastSync = getClientLastSyncTimestamp();
        if (clientLastSync >= serverGenTime) {
            LOGGER.debug("Scheduled join auto-sync skipped: client up-to-date (client={}, server={})",
                    clientLastSync, serverGenTime);
            return false;
        }
        LOGGER.info("Scheduled join auto-sync: client behind server (client={}, server={})",
                clientLastSync, serverGenTime);
        return true;
    }

    public static boolean shouldAutoSyncOnJoin(long serverGenTime, int intervalMinutes) {
        if (!ModConfig.CLIENT.isAutoSyncEnabled()) {
            LOGGER.debug("Join auto-sync skipped: client auto-sync disabled");
            return false;
        }
        if (hasPendingResume()) {
            LOGGER.info("Join auto-sync: resuming interrupted sync");
            return true;
        }

        if (serverUpdateMode == UpdateMode.DISABLED) {
            LOGGER.debug("Join auto-sync skipped: server incremental updates disabled");
            return false;
        }
        if (serverUpdateMode == UpdateMode.SCHEDULED) {
            LOGGER.debug("Join auto-sync: SCHEDULED mode, checking timestamps...");
            return shouldSyncScheduledOnJoin(serverGenTime);
        }
        LOGGER.debug("Join auto-sync skipped: unknown update mode {}", serverUpdateMode);
        return false;
    }

    public static boolean hasPendingResume() {
        try {
            Path baseDir = ClientTimestampCache.getLastBaseDir();
            if (baseDir == null) {
                return false;
            }
            ClientTimestampCache cache = ClientTimestampCache.getInstance(baseDir);
            return cache != null && cache.cacheFileExists() && cache.needsResume();
        } catch (Exception e) {
            LOGGER.debug("Failed to check pending resume: {}", e.getMessage());
            return false;
        }
    }

    public static void schedule(Runnable task, int delaySeconds) {
        cancelPending();
        pendingTask = EXECUTOR.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.error("Auto-sync task failed", e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    public static void markStarted() {
        active = true;
        lastAutoSyncTimeMs = System.currentTimeMillis();
    }

    public static void touchSyncTime() {
        lastAutoSyncTimeMs = System.currentTimeMillis();
    }

    public static void markComplete() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    public static void cancel() {
        active = false;
        cancelPending();
    }

    private static void cancelPending() {
        if (pendingTask != null) {
            pendingTask.cancel(false);
            pendingTask = null;
        }
    }

    public static void shutdown() {
        cancel();
        resetServerPolicy();
        EXECUTOR.shutdownNow();
    }

    private static long getClientLastSyncTimestamp() {
        try {
            Path baseDir = ClientTimestampCache.getLastBaseDir();
            if (baseDir == null) return 0;

            ClientTimestampCache cache = ClientTimestampCache.getInstance(baseDir);
            if (cache == null) return 0;

            return cache.getAll().values().stream()
                .mapToLong(ClientMeta::timestampSeconds)
                .max().orElse(0);
        } catch (Exception e) {
            LOGGER.debug("Failed to get client last sync timestamp: {}", e.getMessage());
            return 0;
        }
    }
}
