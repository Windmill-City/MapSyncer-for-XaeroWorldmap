package com.mapsyncer.client;

import com.mapsyncer.network.impl.ForgeNetworkHandler;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.SyncManifestPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.ClientMeta;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
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
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPacketHandler.class);

    private static final ClientSyncSession session = ClientSyncSession.get();

    private static final long PART_STALE_TIMEOUT_MS = 5 * 60 * 1000L;

    private static final long MANIFEST_PART_STALE_TIMEOUT_MS = 5 * 60 * 1000L;

    private static final Set<XaeroMapDataHandler.RegionCoord> updatedRegionCoords = ConcurrentHashMap.newKeySet();

    private static final Set<XaeroMapDataHandler.RegionCoord> loadedRegions = ConcurrentHashMap.newKeySet();

    private record PartEntry(ChunkMapData[] parts, long firstArrivedMs) {}

    private static final ConcurrentHashMap<String, PartEntry> partBuffer = new ConcurrentHashMap<>();

    private static volatile boolean expectManifest = false;
    private static final Map<Integer, SyncManifestPayload> manifestParts = new ConcurrentHashMap<>();
    private static volatile int manifestTotalParts = 0;
    private static volatile long manifestFirstPartArrivedMs = 0;

    private static final ConcurrentLinkedQueue<String> pendingRegionPaths = new ConcurrentLinkedQueue<>();
    private static volatile boolean regionRequestInFlight = false;

    private static volatile int syncTotal = 0;
    private static volatile int syncProcessed = 0;
    private static volatile int syncFailed = 0;
    private static final AtomicInteger syncPendingWrites = new AtomicInteger(0);

    private static volatile long syncStartMs = 0;

    private static final AtomicInteger requestCounter = new AtomicInteger(0);

    public static void registerHandlers() {
        var handler = ForgeNetworkHandler.get();
        handler.registerSyncResponseHandler(MapPacketHandler::handleSyncResponse);
        handler.registerSyncManifestHandler(MapPacketHandler::handleSyncManifest);
    }

    public static void prepareJoinSync() {
        session.invalidate();
        session.begin();
        expectManifest = true;
        manifestParts.clear();
        manifestTotalParts = 0;
        manifestFirstPartArrivedMs = 0;
        pendingRegionPaths.clear();
        regionRequestInFlight = false;
        syncTotal = 0;
        syncProcessed = 0;
        syncFailed = 0;
        syncPendingWrites.set(0);
        updatedRegionCoords.clear();
        loadedRegions.clear();
        partBuffer.clear();
        requestCounter.set(0);
        syncStartMs = System.currentTimeMillis();
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance()
                    .player
                    .displayClientMessage(ChatUtils.prefix().append(ChatUtils.desc("mapsyncer.autosync.start")), false);
        }
        LOGGER.info("Prepared join sync: waiting for server-pushed manifest (generation={})", session.generation());
    }

    public static void clearReceivedChunks() {
        updatedRegionCoords.clear();
    }

    public static void clearSyncData() {
        session.invalidate();
        expectManifest = false;
        manifestParts.clear();
        manifestTotalParts = 0;
        manifestFirstPartArrivedMs = 0;
        pendingRegionPaths.clear();
        regionRequestInFlight = false;
        syncTotal = 0;
        syncProcessed = 0;
        syncFailed = 0;
        syncPendingWrites.set(0);
        updatedRegionCoords.clear();
        loadedRegions.clear();
        partBuffer.clear();
        requestCounter.set(0);
        LOGGER.info(
                "Cleared sync data (was: pending={}, inFlight={}, writes={}, received={}, partBufferKeys={}, syncTotal={}, syncProcessed={})",
                pendingRegionPaths.size(),
                regionRequestInFlight,
                syncPendingWrites.get(),
                updatedRegionCoords.size(),
                partBuffer.size(),
                syncTotal,
                syncProcessed);
    }

    public static void onDisconnect() {
        clearSyncData();
        XaeroReflectionHelper.clearCache();
        XaeroMapDataHandler.clearRegionTracking();
        ClientHashManager.shutdown();
        ClientSyncWriteQueue.shutdown();
        ClientTimestampCache.resetInstance();
        LOGGER.info("Client disconnected, all resources cleaned up");
    }

    private static void handleSyncManifest(SyncManifestPayload payload, Supplier<NetworkEvent.Context> context) {
        final int generationAtEnqueue = session.generation();
        ForgeNetworkHandler.enqueueWork(context, () -> {
            if (!session.isCurrent(generationAtEnqueue)) {
                LOGGER.debug("Ignoring stale sync manifest after disconnect/clear");
                return;
            }

            LOGGER.info(
                    "[SYNC] <- manifest: entries={}, part={}/{} (receiving={}, expectManifest={})",
                    payload.timestamps().size(),
                    payload.partIndex(),
                    payload.totalParts(),
                    session.isReceiving(),
                    expectManifest);

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
                    clearSyncData();
                    return;
                }
                if (manifestParts.size() < manifestTotalParts) {
                    return;
                }
                Map<String, Long> merged = new HashMap<>();
                SyncManifestPayload ref = manifestParts.values().iterator().next();
                for (SyncManifestPayload part : manifestParts.values()) {
                    merged.putAll(part.timestamps());
                }
                manifestParts.clear();
                manifestTotalParts = 0;
                resolved = new SyncManifestPayload(merged, ref.worldId(), ref.status());
            }

            handleManifestReceived(resolved, generationAtEnqueue);
        });
    }

    private static void handleManifestReceived(SyncManifestPayload payload, int generation) {
        if (!session.isCurrent(generation)) {
            LOGGER.debug("Ignoring sync manifest for stale generation {}", generation);
            return;
        }

        if (!expectManifest) {
            LOGGER.debug("Ignoring unsolicited sync manifest (no sync expected)");
            manifestParts.clear();
            manifestTotalParts = 0;
            manifestFirstPartArrivedMs = 0;
            return;
        }
        expectManifest = false;

        if (session.isStale()) {
            LOGGER.warn("Sync was stale, clearing accumulated data");
            clearSyncData();
            return;
        }

        String status = payload.status();
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        ClientTimestampCache tsCache =
                serverDir != null && serverDir.toFile().exists() ? ClientTimestampCache.getInstance(serverDir) : null;

        if ("no_cache".equals(status) || "dim_not_available".equals(status)) {
            LOGGER.info("Server returned error status: {}, aborting sync", status);
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance()
                        .player
                        .displayClientMessage(ChatUtils.desc("mapsyncer.autosync.no_server_map"), false);
            }
            clearSyncData();
            return;
        }

        if (serverDir == null) {
            LOGGER.error("Unable to resolve server directory, cannot compute diff sync");
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance()
                        .player
                        .displayClientMessage(ChatUtils.error("mapsyncer.sync.server_dir_missing"), false);
            }
            clearSyncData();
            return;
        }

        Map<String, Long> serverTimestamps = payload.timestamps();
        if (serverTimestamps.isEmpty()) {
            LOGGER.info("Server manifest is empty, nothing to sync");
            finishUpToDate(tsCache);
            return;
        }

        ClientHashManager.computeMetaForSyncAsync(serverDir, result -> {
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
                        mc.player.displayClientMessage(ChatUtils.error("mapsyncer.sync.hash_scan_failed"), false);
                    }
                    clearSyncData();
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

                LOGGER.info(
                        "Manifest comparison: {} server regions, {} already up-to-date, {} need update",
                        serverTimestamps.size(),
                        upToDateCount,
                        diff.size());

                if (diff.isEmpty()) {
                    finishUpToDate(tsCache);
                    return;
                }

                int playerBlockX = mc.player.getBlockX();
                int playerBlockZ = mc.player.getBlockZ();
                List<String> ordered = orderByViewDistance(diff.keySet(), playerBlockX, playerBlockZ);

                pendingRegionPaths.clear();
                pendingRegionPaths.addAll(ordered);
                syncTotal = ordered.size();
                syncProcessed = 0;
                syncFailed = 0;
                regionRequestInFlight = false;
                LOGGER.info(
                        "[SYNC] per-region pull started: {} regions to fetch (generation={})", syncTotal, generation);
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

    private static void handleSyncResponse(SyncResponsePayload payload, Supplier<NetworkEvent.Context> context) {
        final int generationAtEnqueue = session.generation();
        ForgeNetworkHandler.enqueueWork(context, () -> {
            if (!session.isCurrent(generationAtEnqueue)) {
                LOGGER.debug("Ignoring stale sync response after disconnect/clear");
                return;
            }

            LOGGER.info(
                    "[SYNC] <- response: chunks={}, complete={}, worldId={}, status={} (receiving={}, inFlight={}, pending={}, writes={}, partBufferKeys={})",
                    payload.chunks().size(),
                    payload.isComplete(),
                    payload.worldId(),
                    payload.status(),
                    session.isReceiving(),
                    regionRequestInFlight,
                    pendingRegionPaths.size(),
                    syncPendingWrites.get(),
                    partBuffer.size());

            if (session.isStale()) {
                LOGGER.warn("Sync was stale, clearing accumulated data");
                clearSyncData();
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance()
                            .player
                            .displayClientMessage(ChatUtils.error("mapsyncer.sync.timeout"), false);
                }
                return;
            }

            if (!session.isReceiving()) {
                session.begin();
                LOGGER.info("Starting sync (per-region pull mode)");
                if (!initializeReflectionCache()) {
                    session.markReflectionFailed();
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance()
                                .player
                                .displayClientMessage(ChatUtils.error("mapsyncer.sync.reflection_failed"), false);
                    }
                }
            }

            Minecraft mc = Minecraft.getInstance();
            Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
            ClientTimestampCache tsCache =
                    serverDir != null && serverDir.toFile().exists()
                            ? ClientTimestampCache.getInstance(serverDir)
                            : null;

            cleanStaleParts();

            for (ChunkMapData chunk : payload.chunks()) {
                ChunkMapData assembled = assemblePart(chunk);
                if (assembled == null) {
                    continue;
                }

                if (serverDir == null) {
                    LOGGER.error(
                            "Unable to resolve server directory, skipping region ({}, {})",
                            assembled.regionX,
                            assembled.regionZ);
                    syncFailed++;
                    continue;
                }

                XaeroMapDataHandler.RegionCoord coord =
                        new XaeroMapDataHandler.RegionCoord(assembled.regionX, assembled.regionZ, assembled.caveLayer);
                updatedRegionCoords.add(coord);

                boolean syncingCaveDimension =
                        DimensionPathMapping.getInstance().isNether(assembled.dimension);
                boolean shouldProcess = syncingCaveDimension ? !assembled.isSurfaceLayer() : assembled.isSurfaceLayer();

                syncPendingWrites.incrementAndGet();
                final int gen = generationAtEnqueue;
                final ClientTimestampCache batchTsCache = tsCache;
                final boolean processRegion = shouldProcess;

                ClientSyncWriteQueue.submit(assembled, serverDir, payload.worldId(), tsCache, writeResult -> {
                    mc.execute(() -> {
                        try {
                            if (!session.isCurrent(gen)) {
                                return;
                            }

                            if (writeResult == null) {
                                LOGGER.error(
                                        "Region ({}, {}) write failed, skipping load ({} bytes)",
                                        assembled.regionX,
                                        assembled.regionZ,
                                        assembled.data.length);
                                syncFailed++;
                                if (batchTsCache != null) {
                                    batchTsCache.remove(XaeroMapDataHandler.buildRelativePathForCache(assembled));
                                }
                            } else if (processRegion && !session.reflectionFailed()) {
                                triggerSingleRegionLoad(coord);
                            }
                            LOGGER.info(
                                    "[SYNC-WRITE] region=({},{}) layer={} result={} (writesBeforeDec={})",
                                    assembled.regionX,
                                    assembled.regionZ,
                                    assembled.caveLayer,
                                    writeResult == null ? "FAILED" : "ok",
                                    syncPendingWrites.get());
                        } finally {
                            syncPendingWrites.decrementAndGet();
                            maybeCompleteSync();
                        }
                    });
                });
            }

            if (payload.isComplete()) {
                session.touch();
                regionRequestInFlight = false;
                syncProcessed++;
                LOGGER.info(
                        "[SYNC] complete signal: syncProcessed={}/{} (pending={}, writes={}, partBufferKeys={})",
                        syncProcessed,
                        syncTotal,
                        pendingRegionPaths.size(),
                        syncPendingWrites.get(),
                        partBuffer.size());
                requestNextRegion(generationAtEnqueue);
            }
        });
    }

    private static void requestNextRegion(int generation) {
        if (!session.isCurrent(generation)) return;
        if (!session.isReceiving()) return;
        if (regionRequestInFlight) return;

        String path = pendingRegionPaths.poll();
        if (path == null) {
            LOGGER.info(
                    "[SYNC] requestNextRegion: queue empty -> maybeCompleteSync (inFlight={}, writes={})",
                    regionRequestInFlight,
                    syncPendingWrites.get());
            maybeCompleteSync();
            return;
        }

        regionRequestInFlight = true;
        Map<String, ClientMeta> single = new HashMap<>();
        single.put(path, new ClientMeta(0, HashUtils.DEFAULT_HASH));
        ForgeNetworkHandler.get().sendToServer(new SyncRequestPayload(single, false, "", true));
        int seq = requestCounter.incrementAndGet();
        LOGGER.info(
                "[SYNC] -> request #{}: {} (pendingLeft={}, syncProcessed={}/{})",
                seq,
                path,
                pendingRegionPaths.size(),
                syncProcessed,
                syncTotal);
    }

    private static void maybeCompleteSync() {
        if (!session.isReceiving()) {
            return;
        }
        boolean pending = !pendingRegionPaths.isEmpty();
        boolean inflight = regionRequestInFlight;
        int writes = syncPendingWrites.get();
        if (pending || inflight || writes > 0) {
            LOGGER.debug(
                    "[SYNC-GUARD] holding completion: pending={} ({} paths), inFlight={}, writes={}, partBufferKeys={}",
                    pending,
                    pendingRegionPaths.size(),
                    inflight,
                    writes,
                    partBuffer.size());
            return;
        }
        LOGGER.info("[SYNC-GUARD] completion guard cleared -> completeSync (partBufferKeys={})", partBuffer.size());
        completeSync();
    }

    private static void completeSync() {
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        ClientTimestampCache tsCache =
                serverDir != null && serverDir.toFile().exists() ? ClientTimestampCache.getInstance(serverDir) : null;

        int totalReceived = updatedRegionCoords.size();
        LOGGER.info(
                "Sync complete: {} regions processed (syncTotal={}, syncProcessed={}, syncFailed={}, uncompletedRequests={}, pending={}, inFlight={}, writes={}, partBufferKeys={})",
                totalReceived,
                syncTotal,
                syncProcessed,
                syncFailed,
                syncTotal - syncProcessed,
                pendingRegionPaths.size(),
                regionRequestInFlight,
                syncPendingWrites.get(),
                partBuffer.size());

        if (Minecraft.getInstance().player != null) {
            if (totalReceived > 0) {
                if (syncFailed > 0) {
                    Minecraft.getInstance()
                            .player
                            .displayClientMessage(ChatUtils.error("mapsyncer.sync.partial"), false);
                }
                long elapsed = Math.max(0, (System.currentTimeMillis() - syncStartMs) / 1000);
                Minecraft.getInstance()
                        .player
                        .displayClientMessage(
                                ChatUtils.success("mapsyncer.sync.completed", totalReceived, elapsed), false);
            } else {
                Minecraft.getInstance()
                        .player
                        .displayClientMessage(ChatUtils.desc("mapsyncer.command.no_regions"), false);
            }
        }

        if (!updatedRegionCoords.isEmpty()) {
            XaeroMapDataHandler.recordUpdatedRegionCoords(updatedRegionCoords);
        }

        if (tsCache != null) {
            ClientSyncWriteQueue.saveTimestampCacheAsync(tsCache);
        }

        clearSyncData();
        XaeroReflectionHelper.clearCache();
    }

    private static void finishUpToDate(@Nullable ClientTimestampCache tsCache) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(ChatUtils.desc("mapsyncer.command.no_regions"), false);
        }
        if (tsCache != null) {
            ClientSyncWriteQueue.saveTimestampCacheAsync(tsCache);
        }
        clearSyncData();
        XaeroReflectionHelper.clearCache();
    }

    private static boolean initializeReflectionCache() {
        if (XaeroReflectionHelper.isInitialized()) {
            LOGGER.debug("Reflection cache already initialized, skipping");
            return true;
        }

        LOGGER.info("Initializing reflection API cache...");
        boolean initSuccess = XaeroReflectionHelper.initialize();

        if (initSuccess) {
            LOGGER.info("XaeroReflectionHelper initialized successfully");
            boolean regionDetectSuccess = XaeroReflectionHelper.setRegionDetectionComplete(true);
            if (regionDetectSuccess) {
                LOGGER.info("regionDetectionComplete set to true, reflection ready");
            } else {
                LOGGER.warn("setRegionDetectionComplete failed, getLeafMapRegion may return null");
            }
            return true;
        }

        LOGGER.error("XaeroReflectionHelper initialization failed, reflection unavailable");
        return false;
    }

    private static void triggerSingleRegionLoad(XaeroMapDataHandler.RegionCoord coord) {
        try {
            if (!XaeroReflectionHelper.isInitialized()) {
                LOGGER.warn(
                        "Reflection cache not initialized, cannot load region ({}, {}) layer={}",
                        coord.x(),
                        coord.z(),
                        coord.caveLayer());
                return;
            }

            if (loadedRegions.contains(coord)) {
                LOGGER.debug(
                        "Region ({}, {}) layer={} already loaded, skipping", coord.x(), coord.z(), coord.caveLayer());
                return;
            }

            Object mapRegion = XaeroReflectionHelper.getLeafMapRegion(coord.caveLayer(), coord.x(), coord.z(), true);
            if (mapRegion == null) {
                LOGGER.warn("Cannot create MapRegion ({}, {}) layer={}", coord.x(), coord.z(), coord.caveLayer());
                return;
            }

            if (!XaeroReflectionHelper.prepareRegionLoad(mapRegion)) {
                LOGGER.warn(
                        "Region ({}, {}) layer={} load preparation failed", coord.x(), coord.z(), coord.caveLayer());
                return;
            }

            if (!XaeroReflectionHelper.setLoadState(mapRegion, XaeroReflectionHelper.LOAD_STATE_CLEARED)) {
                LOGGER.warn("Region ({}, {}) layer={} setLoadState failed", coord.x(), coord.z(), coord.caveLayer());
                return;
            }

            if (!XaeroReflectionHelper.requestLoad(mapRegion, "sync", false)) {
                LOGGER.warn("Region ({}, {}) layer={} requestLoad failed", coord.x(), coord.z(), coord.caveLayer());
                return;
            }

            loadedRegions.add(coord);
        } catch (Exception e) {
            LOGGER.error(
                    "Failed to load region ({}, {}) layer={}: {}",
                    coord.x(),
                    coord.z(),
                    coord.caveLayer(),
                    e.getMessage(),
                    e);
        }
    }

    private static String partKey(ChunkMapData chunk) {
        return chunk.regionX + "," + chunk.regionZ + "," + chunk.dimension + "," + chunk.caveLayer;
    }

    private static @Nullable ChunkMapData assemblePart(ChunkMapData chunk) {
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
        LOGGER.debug(
                "[SYNC-PART] {} part {}/{} arrived, buffered {}/{}",
                key,
                chunk.partIndex + 1,
                chunk.totalParts,
                countNonNull(parts),
                chunk.totalParts);

        if (now - entry.firstArrivedMs() > PART_STALE_TIMEOUT_MS) {
            partBuffer.remove(key);
            LOGGER.warn(
                    "Chunk part assembly timed out for {} ({}ms), discarding {} received parts",
                    key,
                    now - entry.firstArrivedMs(),
                    countNonNull(parts));
            return null;
        }

        for (ChunkMapData p : parts) {
            if (p == null) return null;
        }

        LOGGER.info("[SYNC-PART] {} fully assembled ({} parts)", key, chunk.totalParts);
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
        return new ChunkMapData(
                first.regionX, first.regionZ, first.dimension, assembled, first.timestampSeconds, first.caveLayer);
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
                LOGGER.warn(
                        "Cleaned stale part buffer for {} ({}ms overdue)",
                        e.getKey(),
                        now - e.getValue().firstArrivedMs());
            }
        }
    }
}
