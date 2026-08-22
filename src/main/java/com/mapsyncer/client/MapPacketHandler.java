package com.mapsyncer.client;

import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncManifestPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import com.mapsyncer.client.ClientSyncSession;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.UpdateMode;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.ClientMeta;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class MapPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPacketHandler.class);

    private static final ClientSyncSession session = ClientSyncSession.get();

    private static int ticksUntilNextLoad = 0;

    private static boolean isViewOnly(int intervalTicks) {
        return intervalTicks == 0;
    }

    private static boolean isUnlimited(int intervalTicks) {
        return intervalTicks == -1;
    }

    private static boolean shouldDrainOne(int intervalTicks) {
        if (intervalTicks <= 0) {
            return false;
        }
        if (ticksUntilNextLoad > 0) {
            ticksUntilNextLoad--;
            return false;
        }
        ticksUntilNextLoad = intervalTicks - 1;
        return true;
    }

    private static void resetThrottle() {
        ticksUntilNextLoad = 0;
    }


    public static boolean isSyncInProgress() {
        return session.phase() == ClientSyncSession.SyncPhase.RECEIVING
                || ClientSyncWriteQueue.hasPendingWrites()
                || pendingWriteApplyCallbacks.get() > 0;
    }

    public static boolean isBackgroundReloadPending() {
        return session.phase() == ClientSyncSession.SyncPhase.DRAINING_RELOAD || !pendingRegionLoads.isEmpty();
    }

    private static volatile boolean serverInstalled = false;

    private static volatile String serverVersion = "";

    private static volatile Path lastMwDir = null;

    private static volatile long lastSyncCompleteTs = 0;

    private static volatile int lastProgressProcessed = -1;

    private static volatile int lastProgressTotal = -1;

    private static volatile long lastProgressTime = 0;

    private static final long PROGRESS_DEDUP_MS = 100;

    private static final Set<XaeroMapDataHandler.RegionCoord> updatedRegionCoords = ConcurrentHashMap.newKeySet();

    private static final Set<XaeroMapDataHandler.RegionCoord> loadedRegions = ConcurrentHashMap.newKeySet();

    private static final ConcurrentLinkedQueue<PendingRegionLoad> pendingRegionLoads = new ConcurrentLinkedQueue<>();

    private record PendingRegionLoad(int regionX, int regionZ, int caveLayer) {}

    private static final long PART_STALE_TIMEOUT_MS = 2 * 60 * 1000;

    private record PartEntry(ChunkMapData[] parts, long firstArrivedMs) {}
    private static final ConcurrentHashMap<String, PartEntry> partBuffer = new ConcurrentHashMap<>();

    private static volatile boolean syncFinishRequested = false;
    private static volatile ClientTimestampCache syncFinishTsCache = null;

    private static final AtomicInteger pendingWriteApplyCallbacks = new AtomicInteger(0);

    private static volatile boolean pendingSyncAll = false;
    private static volatile String pendingTargetDim = "";
    private static volatile boolean pendingSilent = false;

    private static final long MANIFEST_PART_STALE_TIMEOUT_MS = 2 * 60 * 1000;
    private static final Map<Integer, SyncManifestPayload> manifestParts = new ConcurrentHashMap<>();
    private static volatile int manifestTotalParts = 0;
    private static volatile long manifestFirstPartArrivedMs = 0;

    private static final ConcurrentLinkedQueue<String> pendingRegionPaths = new ConcurrentLinkedQueue<>();

    private static volatile boolean regionRequestInFlight = false;

    private static volatile int syncTotal = 0;

    private static volatile int syncProcessed = 0;

    private static volatile int syncFailed = 0;

    public static void startManifestRequest(boolean syncAll, String targetDim, boolean silent) {
        pendingSyncAll = syncAll;
        pendingTargetDim = targetDim;
        pendingSilent = silent;
        manifestParts.clear();
        manifestTotalParts = 0;
        manifestFirstPartArrivedMs = 0;
    }

    private static String partKey(ChunkMapData chunk) {
        return chunk.regionX + "," + chunk.regionZ + "," + chunk.dimension + "," + chunk.caveLayer;
    }

    private static ChunkMapData assemblePart(ChunkMapData chunk) {
        if (chunk.totalParts <= 1) {
            return chunk;
        }

        if (chunk.totalParts <= 0 || chunk.partIndex < 0 || chunk.partIndex >= chunk.totalParts) {
            LOGGER.warn("Invalid chunk part metadata: index={} total={}", chunk.partIndex, chunk.totalParts);
            return null;
        }

        String key = partKey(chunk);
        long now = System.currentTimeMillis();
        PartEntry entry = partBuffer.compute(key, (k, existing) -> {
            if (existing == null) {
                ChunkMapData[] arr = new ChunkMapData[chunk.totalParts];
                arr[chunk.partIndex] = chunk;
                return new PartEntry(arr, now);
            }
            existing.parts()[chunk.partIndex] = chunk;
            return existing;
        });
        ChunkMapData[] parts = entry.parts();

        if (now - entry.firstArrivedMs() > PART_STALE_TIMEOUT_MS) {
            partBuffer.remove(key);
            LOGGER.warn("Chunk part assembly timed out for {} ({}ms), discarding {} received parts",
                key, now - entry.firstArrivedMs(), countNonNull(parts));
            return null;
        }

        for (ChunkMapData p : parts) {
            if (p == null) return null;
        }

        partBuffer.remove(key);

        int totalLen = 0;
        for (ChunkMapData p : parts) {
            totalLen += p.data.length;
        }
        byte[] assembled = new byte[totalLen];
        int offset = 0;
        for (ChunkMapData p : parts) {
            System.arraycopy(p.data, 0, assembled, offset, p.data.length);
            offset += p.data.length;
        }

        ChunkMapData first = parts[0];
        return new ChunkMapData(first.regionX, first.regionZ, first.dimension,
                assembled, first.timestampSeconds, first.caveLayer);
    }

    private static int countNonNull(ChunkMapData[] parts) {
        int n = 0;
        for (ChunkMapData p : parts) {
            if (p != null) n++;
        }
        return n;
    }

    private static void cleanStaleParts() {
        long now = System.currentTimeMillis();
        for (var it = partBuffer.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now - e.getValue().firstArrivedMs() > PART_STALE_TIMEOUT_MS) {
                it.remove();
                LOGGER.warn("Cleaned stale part buffer for {} ({}ms overdue)",
                    e.getKey(), now - e.getValue().firstArrivedMs());
            }
        }
    }

    public static boolean isSyncStale() {
        return session.isStale();
    }

    public static void clearSyncData() {
        session.invalidate();
        SyncProgressTracker.cancelTracking();
        clearReceivedChunks();
        loadedRegions.clear();
        partBuffer.clear();
        pendingRegionLoads.clear();
        lastMwDir = null;
        syncFinishRequested = false;
        syncFinishTsCache = null;
        pendingWriteApplyCallbacks.set(0);
        manifestParts.clear();
        manifestTotalParts = 0;
        manifestFirstPartArrivedMs = 0;
        pendingRegionPaths.clear();
        regionRequestInFlight = false;
        syncTotal = 0;
        syncProcessed = 0;
        syncFailed = 0;
        LOGGER.info("Cleared sync data to prevent memory leak");
    }

    public static void clearReceivedChunks() {
        if (updatedRegionCoords != null) {
            updatedRegionCoords.clear();
        }
    }

    public static void onDisconnect() {
        AutoSyncManager.cancel();
        resetServerStatus();
        clearSyncData();
        XaeroReflectionHelper.clearCache();
        XaeroMapDataHandler.clearRegionTracking();
        ClientHashManager.shutdown();
        ClientSyncWriteQueue.shutdown();
        ClientTimestampCache.resetInstance();
        LOGGER.info("Client disconnected, all resources cleaned up");
    }

    public static void registerHandlers() {
        var handler = NetworkManager.getHandler();

        handler.registerServerInstalledHandler(MapPacketHandler::onServerInstalled);

        handler.registerSyncResponseHandler(MapPacketHandler::handleSyncResponse);

        handler.registerSyncProgressHandler(MapPacketHandler::handleProgressUpdate);

        handler.registerSyncManifestHandler(MapPacketHandler::handleSyncManifest);

        handler.registerSyncRequestHandler((payload, ctx) -> {
            ctx.enqueueWork(() -> {
                if (isSyncStale()) {
                    clearSyncData();
                    LOGGER.warn("Cleared stale sync data before starting new sync");
                }
                updatedRegionCoords.clear();
            });
        });
    }

    public static boolean isServerInstalled() {
        return serverInstalled;
    }

    public static void resetServerStatus() {
        serverInstalled = false;
        serverVersion = "";
        joinSyncScheduled = false;
        AutoSyncManager.resetServerPolicy();
    }

    private static volatile boolean joinSyncScheduled = false;

    public static void onServerInstalled(ServerInstalledPayload payload, PayloadContext ctx) {
        ctx.enqueueWork(() -> {
            try {
                boolean firstAnnounce = !serverInstalled;
                serverInstalled = true;
                serverVersion = payload.version();
                AutoSyncManager.configureFromServer(payload.updateMode());
                LOGGER.info("Server has MapSyncer installed, version: {}, mode={}, joinAutoSync={}",
                        serverVersion, payload.updateMode(),
                        payload.updateMode() != UpdateMode.DISABLED);

                if (!firstAnnounce) {
                    return;
                }

                Minecraft.getInstance().player.displayClientMessage(
                    ChatUtils.prefix().append(ChatUtils.desc(AutoSyncManager.getStatusKey())), false);

                boolean shouldJoinSync = AutoSyncManager.shouldAutoSyncOnJoin(
                        payload.lastGenerationTimestamp());
                LOGGER.info("shouldAutoSyncOnJoin result: {} (serverGenTime={})",
                        shouldJoinSync, payload.lastGenerationTimestamp());
                if (shouldJoinSync && !joinSyncScheduled) {
                    joinSyncScheduled = true;
                    AutoSyncManager.schedule(() -> {
                        Minecraft.getInstance().execute(() -> {
                            if (Minecraft.getInstance().player != null
                                    && !MapPacketHandler.isSyncInProgress()) {
                                Minecraft.getInstance().player.displayClientMessage(
                                    ChatUtils.prefix().append(ChatUtils.desc("mapsyncer.autosync.start")), false);
                                AutoSyncManager.markStarted();
                                MapSyncerCommandLogic.executeSyncAll(true);
                            }
                        });
                    }, 5);
                }
            } catch (Exception e) {
                LOGGER.error("Error processing ServerInstalledPayload", e);
            }
        });
    }

    private static void handleSyncResponse(SyncResponsePayload payload, PayloadContext context) {
        final int generationAtEnqueue = session.generation();
        context.enqueueWork(() -> {
            if (!session.isCurrent(generationAtEnqueue)) {
                LOGGER.debug("Ignoring stale sync response after disconnect/clear");
                return;
            }

            String status = payload.status();
            List<ChunkMapData> chunks = payload.chunks();
            int serverWorldId = payload.worldId();
            ClientSyncSession.SyncOutcome serverOutcome = ClientSyncSession.SyncOutcome.fromServerStatus(status);

            LOGGER.debug("Received sync response: status={}, chunks={}, isComplete={}", status, chunks.size(), payload.isComplete());

            Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
            ClientTimestampCache tsCache = serverDir != null && serverDir.toFile().exists()
                    ? ClientTimestampCache.getInstance(serverDir) : null;

            if (!serverInstalled) {
                serverInstalled = true;
                LOGGER.info("Server confirmed (SyncResponse received), MapSyncer detected");
            }
            SyncProgressTracker.onServerResponded();

            if (serverOutcome == ClientSyncSession.SyncOutcome.HARD_FAIL) {
                LOGGER.info("Server returned error status: {}, aborting sync", status);
                session.setOutcome(ClientSyncSession.SyncOutcome.HARD_FAIL);
                clearSyncData();
                clearReflectionCache();
                SyncProgressTracker.cancelTracking();
                if (tsCache != null) {
                    tsCache.clearSyncState();
                }
                return;
            }

            if (serverOutcome == ClientSyncSession.SyncOutcome.SILENT_SKIP) {
                LOGGER.info("Map is up-to-date, no sync needed");
                session.setOutcome(ClientSyncSession.SyncOutcome.SILENT_SKIP);
                clearSyncData();
                clearReflectionCache();
                SyncProgressTracker.finishUptodate();
                finishJoinAutoSyncIfActive();
                if (tsCache != null) {
                    tsCache.markSyncComplete();
                }
                return;
            }

            if (isSyncStale()) {
                session.setOutcome(ClientSyncSession.SyncOutcome.HARD_FAIL);
                clearSyncData();
                clearReflectionCache();
                LOGGER.warn("Sync was stale, cleared accumulated data");
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(ChatUtils.error("mapsyncer.sync.timeout"), false);
                }
                return;
            }

            if (session.phase() == ClientSyncSession.SyncPhase.IDLE || session.phase() == ClientSyncSession.SyncPhase.DRAINING_RELOAD) {
                session.beginReceiving();
                LOGGER.info("Starting sync (per-region pull mode)");
                if (!initializeReflectionCache()) {
                    session.markReflectionFailed();
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance().player.displayClientMessage(
                                ChatUtils.error("mapsyncer.sync.reflection_failed"), false);
                    }
                }
            }

            Minecraft mc = Minecraft.getInstance();
            cleanStaleParts();
            AtomicInteger batchPending = new AtomicInteger();
            AtomicInteger submittedCount = new AtomicInteger();

            for (ChunkMapData chunk : chunks) {
                ChunkMapData assembled = assemblePart(chunk);
                if (assembled == null) {
                    continue;
                }

                if (serverDir == null) {
                    LOGGER.error("无法获取服务器目录，跳过 region ({}, {})",
                            assembled.regionX, assembled.regionZ);
                    continue;
                }

                XaeroMapDataHandler.RegionCoord coord = new XaeroMapDataHandler.RegionCoord(
                    assembled.regionX, assembled.regionZ, assembled.caveLayer);
                updatedRegionCoords.add(coord);

                boolean syncingCaveDimension = DimensionPathMapping.getInstance().isNether(assembled.dimension);
                boolean shouldProcess = syncingCaveDimension
                    ? !assembled.isSurfaceLayer()
                    : assembled.isSurfaceLayer();

                Set<XaeroMapDataHandler.RegionCoord> viewRegionsForLayer =
                    XaeroMapIntegrator.getViewDistanceRegions(assembled.caveLayer);
                boolean inViewDistance = viewRegionsForLayer.contains(coord);

                submittedCount.incrementAndGet();
                batchPending.incrementAndGet();
                final int gen = generationAtEnqueue;
                final ClientTimestampCache batchTsCache = tsCache;


                pendingWriteApplyCallbacks.incrementAndGet();
                ClientSyncWriteQueue.submit(assembled, serverDir, serverWorldId, tsCache, writeResult -> {
                    mc.execute(() -> {
                        try {
                            if (!session.isCurrent(gen)) {
                                return;
                            }

                            if (writeResult == null) {
                                LOGGER.error("Region ({}, {}) 写入失败，跳过加载（{} bytes）",
                                        assembled.regionX, assembled.regionZ, assembled.data.length);
                                if (batchTsCache != null) {
                                    batchTsCache.remove(
                                            XaeroMapDataHandler.buildRelativePathForCache(assembled));
                                }
                            } else {
                                lastMwDir = writeResult.mwDir();

                                if (shouldProcess && !session.reflectionFailed()) {
                                    if (inViewDistance) {
                                        triggerSingleRegionLoad(coord, assembled.caveLayer, true);
                                    } else {
                                        pendingRegionLoads.add(new PendingRegionLoad(
                                                coord.x(), coord.z(), assembled.caveLayer));
                                    }
                                    LOGGER.debug("区域 ({}, {}) layer={} inView={} 已写入并触发加载",
                                            coord.x(), coord.z(), assembled.caveLayer, inViewDistance);
                                } else if (shouldProcess) {
                                    LOGGER.debug("区域 ({}, {}) 已写入磁盘，反射不可用跳过运行时重载",
                                            coord.x(), coord.z());
                                }
                            }

                            if (batchPending.decrementAndGet() == 0 && batchTsCache != null
                                    && submittedCount.get() > 0) {
                                ClientSyncWriteQueue.saveTimestampCacheAsync(batchTsCache);
                            }
                        } finally {
                            pendingWriteApplyCallbacks.decrementAndGet();
                            tryCompleteSync(gen);
                        }
                    });
                });
            }

            if (payload.isComplete()) {
                session.touch();
                regionRequestInFlight = false;
                syncProcessed++;
                if (submittedCount.get() == 0) {
                    syncFailed++;
                }
                SyncProgressTracker.update(syncProcessed, syncTotal,
                        String.format("Syncing regions %d/%d", syncProcessed, syncTotal));
                requestNextRegion(generationAtEnqueue);
            }
        });
    }

    private static void tryCompleteSync(int generation) {
        if (!syncFinishRequested || ClientSyncWriteQueue.hasPendingWrites()
                || pendingWriteApplyCallbacks.get() > 0) {
            return;
        }
        if (!session.isCurrent(generation)) {
            return;
        }

        syncFinishRequested = false;
        ClientTimestampCache tsCache = syncFinishTsCache;

        int totalReceived = updatedRegionCoords.size();
        LOGGER.info("同步完成: 总计 {} 个区域已处理", totalReceived);

        lastSyncCompleteTs = System.currentTimeMillis();

        ClientSyncSession.SyncOutcome finalOutcome = syncFailed > 0 || session.reflectionFailed()
                ? ClientSyncSession.SyncOutcome.PARTIAL_SUCCESS
                : ClientSyncSession.SyncOutcome.SUCCESS;
        session.setOutcome(finalOutcome);

        if (!updatedRegionCoords.isEmpty()) {
            XaeroMapDataHandler.recordUpdatedRegionCoords(updatedRegionCoords);
            SyncProgressTracker.completeWithCount(totalReceived);

            if (AutoSyncManager.isActive()) {
                AutoSyncManager.markComplete();
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            ChatUtils.success("mapsyncer.autosync.complete"),
                            false);
                }
            }

            if (tsCache != null) {
                tsCache.markSyncComplete();
            }
            notifySyncOutcome(finalOutcome);
        } else {
            LOGGER.info("Sync complete with no data received");
            SyncProgressTracker.finishUptodate();
            finishJoinAutoSyncIfActive();
            if (tsCache != null) {
                tsCache.markSyncComplete();
            }
        }

        clearSyncStateAfterComplete();
        scheduleDeferredReloadCleanup();
    }

    private static void notifySyncOutcome(ClientSyncSession.SyncOutcome outcome) {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        if (outcome == ClientSyncSession.SyncOutcome.PARTIAL_SUCCESS) {
            if (session.reflectionFailed()) {
                Minecraft.getInstance().player.displayClientMessage(
                        ChatUtils.error("mapsyncer.sync.reflection_failed"), false);
            } else {
                Minecraft.getInstance().player.displayClientMessage(
                        ChatUtils.error("mapsyncer.sync.partial"), false);
            }
        }
    }

    private static void handleManifestReceived(SyncManifestPayload payload, int generation) {
        if (!session.isCurrent(generation)) {
            LOGGER.debug("Ignoring sync manifest for stale generation {}", generation);
            return;
        }

        SyncProgressTracker.onServerResponded();

        if (!serverInstalled) {
            serverInstalled = true;
            LOGGER.info("Server confirmed (SyncManifest received), MapSyncer detected");
        }

        String status = payload.status();
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        ClientTimestampCache tsCache = serverDir != null && serverDir.toFile().exists()
                ? ClientTimestampCache.getInstance(serverDir) : null;

        if ("no_cache".equals(status) || "dim_not_available".equals(status)) {
            LOGGER.info("Server returned error status: {}, aborting sync", status);
            session.setOutcome(ClientSyncSession.SyncOutcome.HARD_FAIL);
            clearSyncData();
            clearReflectionCache();
            if (tsCache != null) {
                tsCache.clearSyncState();
            }
            return;
        }

        if (serverDir == null) {
            LOGGER.error("Unable to resolve server directory, cannot compute diff sync");
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(ChatUtils.error("mapsyncer.sync.server_dir_missing"), false);
            }
            session.setOutcome(ClientSyncSession.SyncOutcome.HARD_FAIL);
            clearSyncData();
            clearReflectionCache();
            if (tsCache != null) {
                tsCache.clearSyncState();
            }
            return;
        }

        Map<String, Long> serverTimestamps = payload.timestamps();
        if (serverTimestamps.isEmpty()) {
            LOGGER.info("Server manifest is empty, nothing to sync");
            finishUpToDate(tsCache);
            return;
        }

        Path scanDir = resolveScanDir(serverDir);
        ClientHashManager.computeMetaForSyncAsync(scanDir, result -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (!session.isCurrent(generation)) {
                    LOGGER.debug("Discarding local scan result for stale generation {}", generation);
                    return;
                }
                if (mc.player == null) {
                    return;
                }
                if (!result.isSuccess()) {
                    if (result.failedFiles() > 0) {
                        mc.player.displayClientMessage(
                                ChatUtils.error("mapsyncer.sync.hash_scan_partial", result.failedFiles()), false);
                    } else {
                        mc.player.displayClientMessage(
                                ChatUtils.error("mapsyncer.sync.hash_scan_failed"), false);
                    }
                    session.setOutcome(ClientSyncSession.SyncOutcome.HARD_FAIL);
                    clearSyncData();
                    clearReflectionCache();
                    if (tsCache != null) {
                        tsCache.clearSyncState();
                    }
                    return;
                }

                Map<String, ClientMeta> localMeta = result.meta();
                Map<String, ClientMeta> diff = new HashMap<>();
                int upToDateCount = 0;
                for (Map.Entry<String, Long> entry : serverTimestamps.entrySet()) {
                    String path = entry.getKey();
                    long serverTs = entry.getValue();
                    ClientMeta local = localMeta.get(path);
                    if (local != null && local.timestampSeconds() >= serverTs) {
                        upToDateCount++;
                    } else {
                        diff.put(path, local != null ? local : new ClientMeta(0, HashUtils.DEFAULT_HASH));
                    }
                }

                LOGGER.info("Manifest comparison: {} server regions, {} already up-to-date, {} need update",
                        serverTimestamps.size(), upToDateCount, diff.size());

                if (diff.isEmpty()) {
                    finishUpToDate(tsCache);
                    return;
                }

                Minecraft mc2 = Minecraft.getInstance();
                int playerBlockX = mc2.player != null ? mc2.player.getBlockX() : 0;
                int playerBlockZ = mc2.player != null ? mc2.player.getBlockZ() : 0;
                List<String> ordered = orderByViewDistance(diff.keySet(), playerBlockX, playerBlockZ);

                pendingRegionPaths.clear();
                pendingRegionPaths.addAll(ordered);
                syncTotal = ordered.size();
                syncProcessed = 0;
                syncFailed = 0;
                regionRequestInFlight = false;
                LOGGER.debug("Starting per-region pull for {} regions", syncTotal);
                SyncProgressTracker.update(0, syncTotal, "Sync started");
                requestNextRegion(generation);
            });
        });
    }

    private static List<String> orderByViewDistance(Set<String> paths, int playerBlockX, int playerBlockZ) {
        int playerRegionX = playerBlockX >> 9;
        int playerRegionZ = playerBlockZ >> 9;
        List<String> list = new ArrayList<>(paths);
        list.sort(Comparator.comparingInt(path -> regionDistance(path, playerRegionX, playerRegionZ)));
        return list;
    }

    private static int regionDistance(String path, int playerRegionX, int playerRegionZ) {
        try {
            String[] parts = path.split("[/\\\\]");
            String fileName = parts[parts.length - 1];
            String[] coords = fileName.split("_");
            int rx = Integer.parseInt(coords[0]);
            int rz = Integer.parseInt(coords[1]);
            return Math.max(Math.abs(rx - playerRegionX), Math.abs(rz - playerRegionZ));
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void requestNextRegion(int generation) {
        if (!session.isCurrent(generation)) return;
        if (regionRequestInFlight) return;

        String path = pendingRegionPaths.poll();
        if (path == null) {
            Path serverDir2 = XaeroMapIntegrator.getCurrentServerDirectory();
            syncFinishTsCache = (serverDir2 != null && serverDir2.toFile().exists())
                    ? ClientTimestampCache.getInstance(serverDir2) : null;
            syncFinishRequested = true;
            tryCompleteSync(generation);
            return;
        }

        regionRequestInFlight = true;
        Map<String, ClientMeta> single = new HashMap<>();
        single.put(path, new ClientMeta(0, HashUtils.DEFAULT_HASH));
        NetworkManager.sendToServer(new SyncRequestPayload(single, false, pendingTargetDim, pendingSilent));
        LOGGER.debug("Requesting region: {}", path);
    }

    private static void handleSyncManifest(SyncManifestPayload payload, PayloadContext context) {
        final int generationAtEnqueue = session.generation();
        context.enqueueWork(() -> {
            if (!session.isCurrent(generationAtEnqueue)) {
                LOGGER.debug("Ignoring stale sync manifest after disconnect/clear");
                return;
            }

            SyncManifestPayload resolved = payload;
            if (resolved.totalParts() > 1) {
                if (manifestTotalParts == 0) {
                    manifestParts.clear();
                    manifestTotalParts = resolved.totalParts();
                    manifestFirstPartArrivedMs = System.currentTimeMillis();
                }
                manifestParts.put(resolved.partIndex(), resolved);
                if (System.currentTimeMillis() - manifestFirstPartArrivedMs > MANIFEST_PART_STALE_TIMEOUT_MS) {
                    LOGGER.warn("Sync manifest assembly timed out, aborting");
                    manifestParts.clear();
                    manifestTotalParts = 0;
                    session.setOutcome(ClientSyncSession.SyncOutcome.HARD_FAIL);
                    clearSyncData();
                    return;
                }
                if (manifestParts.size() < manifestTotalParts) {
                    return;
                }
                Map<String, Long> merged = new HashMap<>();
                SyncManifestPayload ref = null;
                for (SyncManifestPayload part : manifestParts.values()) {
                    merged.putAll(part.timestamps());
                    if (ref == null) {
                        ref = part;
                    }
                }
                manifestParts.clear();
                manifestTotalParts = 0;
                resolved = new SyncManifestPayload(merged, ref.worldId(), ref.status());
            }

            handleManifestReceived(resolved, generationAtEnqueue);
        });
    }

    private static Path resolveScanDir(Path serverDir) {
        if (pendingSyncAll) {
            return serverDir;
        }
        if (pendingTargetDim == null || pendingTargetDim.isEmpty()) {
            return serverDir;
        }
        Path dimDir = serverDir.resolve(pendingTargetDim);
        Path mwDir = MapSyncerCommandLogic.findMwDir(dimDir);
        return mwDir != null ? mwDir : dimDir;
    }

    private static void finishUpToDate(ClientTimestampCache tsCache) {
        session.setOutcome(ClientSyncSession.SyncOutcome.SILENT_SKIP);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !pendingSilent) {
            mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.no_regions"), false);
        }
        finishJoinAutoSyncIfActive();
        if (tsCache != null) {
            tsCache.markSyncComplete();
        }
        clearSyncData();
        clearReflectionCache();
        SyncProgressTracker.finishUptodate();
    }

    private static void handleProgressUpdate(SyncProgressPayload payload, PayloadContext context) {
        context.enqueueWork(() -> {
            String status = payload.status();
            if (status != null && status.startsWith("aborted")) {
                SyncProgressTracker.cancelTracking();
                MapPacketHandler.clearSyncData();
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    if (status.contains("timeout")) {
                        mc.player.displayClientMessage(
                                ChatUtils.error("mapsyncer.sync.server_timeout"), false);
                    } else {
                        mc.player.displayClientMessage(
                                ChatUtils.error("mapsyncer.sync.cancelled"), false);
                    }
                }
                return;
            }

            if (AutoSyncManager.isActive()) return;

            int processed = payload.processed();
            int total = payload.total();
            long now = System.currentTimeMillis();
            if (processed == lastProgressProcessed && total == lastProgressTotal
                    && now - lastProgressTime < PROGRESS_DEDUP_MS) {
                return;
            }
            lastProgressProcessed = processed;
            lastProgressTotal = total;
            lastProgressTime = now;
            SyncProgressTracker.update(processed, total, payload.status());
        });
    }

    private static void resumeChunkUpdatesIfIdle() {
        if (!pendingRegionLoads.isEmpty()) {
            return;
        }
        if (session.phase() == ClientSyncSession.SyncPhase.DRAINING_RELOAD) {
            session.completeSession();
            LOGGER.info("Deferred reload queue drained, sync session idle");
        }
    }

    private static void clearSyncStateAfterComplete() {
        updatedRegionCoords.clear();
        loadedRegions.clear();
        partBuffer.clear();
        lastMwDir = null;
    }

    private static void scheduleDeferredReloadCleanup() {
        int intervalTicks;
        try {
            intervalTicks = ModConfig.CLIENT.getMapRegionLoadIntervalTicks();
        } catch (IllegalStateException e) {
            intervalTicks = 1;
        }
        if (isViewOnly(intervalTicks) || pendingRegionLoads.isEmpty()) {
            pendingRegionLoads.clear();
            clearReflectionCache();
            resumeChunkUpdatesIfIdle();
            return;
        }
        session.beginDrainingReload();
        drainPendingLoadQueue();
        finishDeferredReloadCleanupIfDone();
    }

    private static void finishDeferredReloadCleanupIfDone() {
        if (session.phase() != ClientSyncSession.SyncPhase.DRAINING_RELOAD || !pendingRegionLoads.isEmpty()) {
            return;
        }
        clearReflectionCache();
        resumeChunkUpdatesIfIdle();
        LOGGER.debug("视距外 region 重载队列已排空，反射缓存已释放");
    }

    private static void clearSyncState() {
        updatedRegionCoords.clear();
        loadedRegions.clear();
        partBuffer.clear();
        pendingRegionLoads.clear();
        lastMwDir = null;
        resetThrottle();
    }

    private static void clearReflectionCache() {
        XaeroReflectionHelper.clearCache();
    }

    private static boolean initializeReflectionCache() {
        if (XaeroReflectionHelper.isInitialized()) {
            LOGGER.debug("反射缓存已初始化，跳过重复初始化");
            return true;
        }

        LOGGER.info("开始初始化反射 API 缓存...");
        boolean initSuccess = XaeroReflectionHelper.initialize();

        if (initSuccess) {
            LOGGER.info("XaeroReflectionHelper 初始化成功");
            boolean regionDetectSuccess = XaeroReflectionHelper.setRegionDetectionComplete(true);
            if (regionDetectSuccess) {
                LOGGER.info("regionDetectionComplete 设置为 true，反射功能就绪");
            } else {
                LOGGER.warn("regionDetectionComplete 设置失败，getLeafMapRegion 可能会返回 null");
            }
            return true;
        }

        LOGGER.error("XaeroReflectionHelper 初始化失败！反射功能完全不可用");
        LOGGER.error("可能原因：");
        LOGGER.error("  1. Xaero's World Map 模组未安装");
        LOGGER.error("  2. Xaero 版本与 MapSyncer 不兼容");
        LOGGER.error("  3. 类加载器问题");
        LOGGER.error("地图同步功能将无法正常工作，数据会写入文件但不会触发重新加载");
        return false;
    }

    private static void triggerSingleRegionLoad(XaeroMapDataHandler.RegionCoord coord, int caveLayer, boolean inViewDistance) {
        boolean success = false;
        try {
            if (!XaeroReflectionHelper.isInitialized()) {
                LOGGER.warn("反射缓存未初始化，无法加载区域 ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
                return;
            }

            if (loadedRegions.contains(coord)) {
                LOGGER.debug("区域 ({}, {}) layer={} 已加载，跳过", coord.x(), coord.z(), caveLayer);
                success = true;
                return;
            }

            Object mapRegion = XaeroReflectionHelper.getLeafMapRegion(caveLayer, coord.x(), coord.z(), true);
            if (mapRegion == null) {
                LOGGER.warn("无法创建 MapRegion ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
                return;
            }

            String regionWorldId = XaeroReflectionHelper.getWorldId(mapRegion);
            String regionDimId = XaeroReflectionHelper.getDimId(mapRegion);
            String regionMwId = XaeroReflectionHelper.getMwId(mapRegion);
            LOGGER.info("Region ({}, {}) 属性: worldId={}, dimId={}, mwId={}, lastMwDir={}",
                coord.x(), coord.z(), regionWorldId, regionDimId, regionMwId, lastMwDir);

            if (!XaeroReflectionHelper.prepareRegionLoad(mapRegion)) {
                LOGGER.warn("区域 ({}, {}) layer={} 准备加载失败，跳过此区域", coord.x(), coord.z(), caveLayer);
                return;
            }

            if (!XaeroReflectionHelper.setLoadState(mapRegion, XaeroReflectionHelper.LOAD_STATE_CLEARED)) {
                LOGGER.warn("区域 ({}, {}) layer={} 设置 loadState 失败，跳过此区域", coord.x(), coord.z(), caveLayer);
                return;
            }

            String reason = inViewDistance ? "sync view" : "sync outside";
            if (!XaeroReflectionHelper.requestLoad(mapRegion, reason, true)) {
                LOGGER.warn("区域 ({}, {}) layer={} 请求加载失败", coord.x(), coord.z(), caveLayer);
                return;
            }

            if (inViewDistance) {
                LOGGER.debug("区域 ({}, {}) layer={} 视距内，插入队头优先加载", coord.x(), coord.z(), caveLayer);
            } else {
                LOGGER.debug("区域 ({}, {}) layer={} 视距外，添加到加载队列", coord.x(), coord.z(), caveLayer);
            }

            loadedRegions.add(coord);
            success = true;
        } catch (Exception e) {
            LOGGER.error("立即加载区域 ({}, {}) layer={} 失败: {}", coord.x(), coord.z(), caveLayer, e.getMessage(), e);
        } finally {
        }
    }

    public static void drainPendingLoadQueue() {
        SyncProgressTracker.onClientTick();
        int intervalTicks;
        try {
            intervalTicks = ModConfig.CLIENT.getMapRegionLoadIntervalTicks();
        } catch (IllegalStateException e) {
            return;
        }
        if (isViewOnly(intervalTicks)) {
            return;
        }
        if (pendingRegionLoads.isEmpty()) {
            return;
        }

        if (isUnlimited(intervalTicks)) {
            PendingRegionLoad pending;
            while ((pending = pendingRegionLoads.poll()) != null) {
                XaeroMapDataHandler.RegionCoord coord = new XaeroMapDataHandler.RegionCoord(
                    pending.regionX(), pending.regionZ(), pending.caveLayer());
                triggerSingleRegionLoad(coord, pending.caveLayer(), false);
            }
            resetThrottle();
            finishDeferredReloadCleanupIfDone();
            return;
        }

        if (!shouldDrainOne(intervalTicks)) {
            return;
        }

        PendingRegionLoad pending = pendingRegionLoads.poll();
        if (pending != null) {
            XaeroMapDataHandler.RegionCoord coord = new XaeroMapDataHandler.RegionCoord(
                pending.regionX(), pending.regionZ(), pending.caveLayer());
            triggerSingleRegionLoad(coord, pending.caveLayer(), false);
        }
        finishDeferredReloadCleanupIfDone();
    }

    public static boolean hasPendingLoads() {
        return !pendingRegionLoads.isEmpty();
    }

    public static void prepareSyncForDimension(String targetDimension) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        String currentXaeroDim = DimensionPathMapping.getInstance().toXaeroDimension(
                mc.level.dimension().location().toString());

        if (targetDimension.equals(currentXaeroDim)) {
            LOGGER.info("Syncing current dimension {}, unloading view distance regions", targetDimension);
            int unloaded = XaeroMapIntegrator.unloadViewDistanceRegions();
            if (unloaded > 0 && mc.player != null) {
                mc.player.displayClientMessage(
                        ChatUtils.desc("mapsyncer.sync.unloading_regions", unloaded), false);
            }
        }
    }

    private static void finishJoinAutoSyncIfActive() {
        if (!AutoSyncManager.isActive()) {
            return;
        }
        AutoSyncManager.markComplete();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    ChatUtils.success("mapsyncer.autosync.complete"), false);
        }
    }
}
