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

    private static volatile ScheduledFuture<?> pendingTask;
    private static volatile boolean active = false;

    private static volatile boolean serverPolicyKnown = false;
    private static volatile UpdateMode serverUpdateMode = UpdateMode.DISABLED;

    public static String getStatusKey() {
        try {
            if (!ModConfig.CLIENT.isAutoSyncEnabled()) {
                return "mapsyncer.autosync.status.client_disabled";
            }
        } catch (IllegalStateException ignored) {

        }
        if (serverUpdateMode == UpdateMode.DISABLED) return "mapsyncer.autosync.status.disabled";
        if (serverUpdateMode == UpdateMode.ON_EMPTY) return "mapsyncer.autosync.status.on_empty";
        return "mapsyncer.autosync.status.daily";
    }

    public static void configureFromServer(UpdateMode mode) {
        serverUpdateMode = mode;
        serverPolicyKnown = true;
    }

    public static void resetServerPolicy() {
        serverPolicyKnown = false;
        serverUpdateMode = UpdateMode.DISABLED;
    }

    public static boolean isServerPolicyKnown() {
        return serverPolicyKnown;
    }

    public static boolean isJoinAutoSyncEnabled() {
        if (!ModConfig.CLIENT.isAutoSyncEnabled()) {
            return false;
        }
        return serverUpdateMode != UpdateMode.DISABLED;
    }

    public static boolean shouldSyncOnJoin(long serverGenTime) {
        if (serverGenTime <= 0) {
            LOGGER.debug("Join auto-sync skipped: server has no generation data");
            return false;
        }
        long clientLastSync = getClientLastSyncTimestamp();
        if (clientLastSync >= serverGenTime) {
            LOGGER.debug("Join auto-sync skipped: client up-to-date (client={}, server={})",
                    clientLastSync, serverGenTime);
            return false;
        }
        LOGGER.info("Join auto-sync: client behind server (client={}, server={})",
                clientLastSync, serverGenTime);
        return true;
    }

    public static boolean shouldAutoSyncOnJoin(long serverGenTime) {
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
        LOGGER.debug("Join auto-sync: {} mode, checking timestamps...", serverUpdateMode);
        return shouldSyncOnJoin(serverGenTime);
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
