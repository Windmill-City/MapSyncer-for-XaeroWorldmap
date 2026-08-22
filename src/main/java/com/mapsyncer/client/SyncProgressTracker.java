package com.mapsyncer.client;

import com.mapsyncer.util.ChatUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SyncProgressTracker {

    private static volatile boolean tracking = false;
    private static volatile boolean hashScanning = false;
    private static volatile int hashScanProcessed = 0;
    private static volatile int hashScanTotal = 0;
    private static volatile int processed = 0;
    private static volatile int total = 0;
    private static volatile String status = "";
    private static volatile long startTime = 0;
    private static volatile boolean receivedFirstResponse = false;

    private static final long SERVER_RESPONSE_TIMEOUT_MS = 60_000;

    private static final int OVERLAY_REFRESH_TICKS = 40;

    private static volatile boolean overlayActive = false;
    private static int overlayTickCounter = 0;

    private static volatile ScheduledExecutorService timeoutChecker = null;
    private static volatile java.util.concurrent.ScheduledFuture<?> timeoutFuture = null;

    public static void startHashScan(int total) {
        hashScanning = true;
        hashScanProcessed = 0;
        hashScanTotal = total;
        setOverlayActive(true);
    }

    public static void updateHashScan(int processed, int total) {
        if (!hashScanning) {
            return;
        }
        hashScanProcessed = processed;
        hashScanTotal = total;
        scheduleOverlayRefresh();
    }

    public static void completeHashScan() {
        hashScanning = false;
        if (!tracking) {
            setOverlayActive(false);
        }
    }

    public static void startTracking() {
        tracking = true;
        processed = 0;
        total = 0;
        status = Component.translatable("mapsyncer.sync.waiting").getString();
        startTime = System.currentTimeMillis();
        receivedFirstResponse = false;

        startTimeoutChecker();
        setOverlayActive(true);
    }

    public static void onServerResponded() {
        if (!receivedFirstResponse) {
            receivedFirstResponse = true;
            stopTimeoutChecker();
        }
    }

    public static void onClientTick() {
        if (!overlayActive) {
            return;
        }
        overlayTickCounter++;
        if (overlayTickCounter >= OVERLAY_REFRESH_TICKS) {
            overlayTickCounter = 0;
            refreshOverlay();
        }
    }

    public static void update(int processed, int total, String status) {
        if (!tracking) {
            return;
        }
        if (!receivedFirstResponse) {
            receivedFirstResponse = true;
            stopTimeoutChecker();
        }

        SyncProgressTracker.processed = processed;
        SyncProgressTracker.total = total;
        SyncProgressTracker.status = status;
        refreshOverlay();
    }

    public static void completeWithCount(int count) {
        tracking = false;
        hashScanning = false;
        stopTimeoutChecker();
        setOverlayActive(false);

        long elapsed = getElapsedSeconds();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !AutoSyncManager.isActive()) {
            ChatUtils.sendChatMessage(ChatUtils.success("mapsyncer.sync.completed", count, elapsed));
        }
    }

    public static void finishUptodate() {
        tracking = false;
        hashScanning = false;
        stopTimeoutChecker();
        setOverlayActive(false);
    }

    public static void cancelTracking() {
        tracking = false;
        hashScanning = false;
        status = Component.translatable("mapsyncer.sync.cancelled").getString();
        stopTimeoutChecker();
        setOverlayActive(false);
    }

    public static boolean isTracking() {
        return tracking;
    }

    public static long getElapsedSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }

    private static void setOverlayActive(boolean active) {
        overlayActive = active;
        overlayTickCounter = 0;
        if (active) {
            refreshOverlay();
        }
    }

    private static void scheduleOverlayRefresh() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(SyncProgressTracker::refreshOverlay);
    }

    private static void refreshOverlay() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !overlayActive) {
            return;
        }
        if (hashScanning) {
            int done = hashScanProcessed;
            int scanTotal = hashScanTotal;
            if (scanTotal > 0) {
                int percent = (done * 100) / scanTotal;
                ChatUtils.sendOverlayMessage(
                        ChatUtils.message("mapsyncer.sync.hash_progress", done, scanTotal, percent));
            } else {
                ChatUtils.sendOverlayMessage(
                        ChatUtils.message("mapsyncer.sync.hash_computing"));
            }
            return;
        }
        if (!tracking) {
            return;
        }
        int currentProcessed = processed;
        int currentTotal = total;
        String currentStatus = status;
        if (currentTotal > 0) {
            int percent = (currentProcessed * 100) / currentTotal;
            ChatUtils.sendOverlayMessage(
                    ChatUtils.message("mapsyncer.sync.progress", currentProcessed, currentTotal, percent));
        } else {
            ChatUtils.sendOverlayMessage(
                    ChatUtils.prefix().append(Component.literal(currentStatus)));
        }
    }

    private static void startTimeoutChecker() {
        if (timeoutFuture != null && !timeoutFuture.isDone()) {
            timeoutFuture.cancel(false);
        }
        if (timeoutChecker == null || timeoutChecker.isShutdown()) {
            timeoutChecker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mapsyncer-sync-progress-timer");
                t.setDaemon(true);
                return t;
            });
        }

        timeoutFuture = timeoutChecker.schedule(() -> {
            if (tracking && !receivedFirstResponse) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        if (!tracking || receivedFirstResponse || mc.player == null) {
                            return;
                        }
                        if (!MapPacketHandler.isServerInstalled()) {
                            ChatUtils.sendChatMessage(ChatUtils.error("mapsyncer.sync.server_not_installed"));
                            cancelTracking();
                        }
                    });
                }
            }
        }, SERVER_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static void stopTimeoutChecker() {
        if (timeoutFuture != null && !timeoutFuture.isDone()) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }
}
