package com.mapsyncer.client;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.ManifestPayload;
import com.mapsyncer.network.MapRequestPayload;
import com.mapsyncer.network.MapResponsePayload;
import com.mapsyncer.network.RegionData;
import com.mapsyncer.network.RegionRef;
import com.mapsyncer.util.ChatUtils;
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

    private static final Set<XaeroMapDataHandler.RegionCoord> updatedRegionCoords = ConcurrentHashMap.newKeySet();

    private static final Set<XaeroMapDataHandler.RegionCoord> loadedRegions = ConcurrentHashMap.newKeySet();

    private record PartEntry(RegionData[] parts, long firstArrivedMs) {}

    private static final ConcurrentHashMap<String, PartEntry> partBuffer = new ConcurrentHashMap<>();

    private static volatile boolean expectManifest = false;

    private static final ConcurrentLinkedQueue<RegionRef> pendingRegionPaths = new ConcurrentLinkedQueue<>();
    private static volatile boolean regionRequestInFlight = false;

    private static volatile int syncTotal = 0;
    private static volatile int syncProcessed = 0;
    private static volatile int syncFailed = 0;
    private static final AtomicInteger syncPendingWrites = new AtomicInteger(0);

    private static volatile long syncStartMs = 0;

    private static final AtomicInteger requestCounter = new AtomicInteger(0);

    public static void prepareJoinSync() {
        session.invalidate();
        session.begin();
        expectManifest = true;
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
        ManifestClient.shutdown();
        ClientSyncWriteQueue.shutdown();
        LOGGER.info("Client disconnected, all resources cleaned up");
    }

    public static void handleSyncManifest(ManifestPayload payload, Supplier<NetworkEvent.Context> context) {
        final int generationAtEnqueue = session.generation();
        MapSyncer.enqueueWork(context, () -> {
            if (!session.isCurrent(generationAtEnqueue)) {
                LOGGER.debug("Ignoring stale sync manifest after disconnect/clear");
                return;
            }

            LOGGER.info(
                    "[SYNC] <- manifest: entries={} (receiving={}, expectManifest={})",
                    payload.timestamps().size(),
                    session.isReceiving(),
                    expectManifest);

            handleManifestReceived(payload, generationAtEnqueue);
        });
    }

    private static void handleManifestReceived(ManifestPayload payload, int generation) {
        if (!session.isCurrent(generation)) {
            LOGGER.debug("Ignoring sync manifest for stale generation {}", generation);
            return;
        }

        if (!expectManifest) {
            LOGGER.debug("Ignoring unsolicited sync manifest (no sync expected)");
            return;
        }
        expectManifest = false;

        if (session.isStale()) {
            LOGGER.warn("Sync was stale, clearing accumulated data");
            clearSyncData();
            return;
        }

        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();

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

        Map<RegionRef, Long> serverTimestamps = payload.timestamps();
        if (serverTimestamps.isEmpty()) {
            LOGGER.info("Server manifest is empty, nothing to sync");
            finishUpToDate();
            return;
        }

        ManifestClient.computeMetaForSyncAsync(serverDir, extractDimIds(serverTimestamps.keySet()), result -> {
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
                                ChatUtils.error("mapsyncer.sync.scan_partial", result.failedFiles()), false);
                    } else {
                        mc.player.displayClientMessage(ChatUtils.error("mapsyncer.sync.scan_failed"), false);
                    }
                    clearSyncData();
                    return;
                }

                Map<RegionRef, Long> localMeta = result.meta();
                Map<RegionRef, Long> diff = new HashMap<>();
                int upToDateCount = 0;
                for (Map.Entry<RegionRef, Long> entry : serverTimestamps.entrySet()) {
                    RegionRef ref = entry.getKey();
                    long serverTs = entry.getValue();
                    Long local = localMeta.get(ref);
                    if (local != null && local >= serverTs) {
                        upToDateCount++;
                    } else {
                        diff.put(ref, local != null ? local : 0L);
                    }
                }

                LOGGER.debug(
                        "Manifest comparison: {} server regions, {} already up-to-date, {} need update",
                        serverTimestamps.size(),
                        upToDateCount,
                        diff.size());

                if (diff.isEmpty()) {
                    finishUpToDate();
                    return;
                }

                int playerBlockX = mc.player.getBlockX();
                int playerBlockZ = mc.player.getBlockZ();
                List<RegionRef> ordered = orderByViewDistance(diff.keySet(), playerBlockX, playerBlockZ);

                pendingRegionPaths.clear();
                pendingRegionPaths.addAll(ordered);
                syncTotal = ordered.size();
                syncProcessed = 0;
                syncFailed = 0;
                regionRequestInFlight = false;
                LOGGER.debug(
                        "[SYNC] per-region pull started: {} regions to fetch (generation={})", syncTotal, generation);
                requestNextRegion(generation);
            });
        });
    }

    private static Set<String> extractDimIds(Set<RegionRef> keys) {
        Set<String> dimIds = new java.util.HashSet<>();
        for (RegionRef ref : keys) {
            if (!ref.dimId().isEmpty()) {
                dimIds.add(ref.dimId());
            }
        }
        return dimIds;
    }

    private static List<RegionRef> orderByViewDistance(Set<RegionRef> keys, int playerBlockX, int playerBlockZ) {
        int playerRegionX = playerBlockX >> 9;
        int playerRegionZ = playerBlockZ >> 9;
        List<RegionRef> list = new ArrayList<>(keys);
        list.sort(Comparator.comparingInt(ref -> ref.regionDistance(playerRegionX, playerRegionZ)));
        return list;
    }

    public static void handleSyncResponse(MapResponsePayload payload, Supplier<NetworkEvent.Context> context) {
        final int generationAtEnqueue = session.generation();
        MapSyncer.enqueueWork(context, () -> {
            if (!session.isCurrent(generationAtEnqueue)) {
                LOGGER.debug("Ignoring stale sync response after disconnect/clear");
                return;
            }

            LOGGER.debug(
                    "[SYNC] <- response: chunks={}, complete={} (receiving={}, inFlight={}, pending={}, writes={}, partBufferKeys={})",
                    payload.chunks().size(),
                    payload.isComplete(),
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
                LOGGER.debug("Starting sync (per-region pull mode)");
                if (!XaeroReflectionHelper.isInitialized()) {
                    session.markReflectionFailed();
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance()
                                .player
                                .displayClientMessage(ChatUtils.error("mapsyncer.sync.reflection_failed"), false);
                    }
                } else {
                    boolean regionDetectSuccess = XaeroReflectionHelper.setRegionDetectionComplete(true);
                    if (regionDetectSuccess) {
                        LOGGER.info("regionDetectionComplete set to true, reflection ready");
                    } else {
                        LOGGER.warn("setRegionDetectionComplete failed, getLeafMapRegion may return null");
                    }
                }
            }

            Minecraft mc = Minecraft.getInstance();
            Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();

            cleanStaleParts();

            if (!XaeroReflectionHelper.isInitialized()) {
                session.markReflectionFailed();
            }

            String worldId = XaeroReflectionHelper.getCurrentWorldId();
            if (worldId == null || worldId.isEmpty()) {
                LOGGER.error(
                        "Unable to resolve current world id from Xaero, skipping {} received chunks",
                        payload.chunks().size());
                syncFailed += payload.chunks().size();
                if (payload.isComplete()) {
                    regionRequestInFlight = false;
                    syncProcessed++;
                    requestNextRegion(generationAtEnqueue);
                }
                return;
            }

            for (RegionData chunk : payload.chunks()) {
                RegionData assembled = assemblePart(chunk);
                if (assembled == null) {
                    continue;
                }

                if (serverDir == null) {
                    LOGGER.error(
                            "Unable to resolve server directory, skipping region ({}, {})",
                            assembled.ref.regionX(),
                            assembled.ref.regionZ());
                    syncFailed++;
                    continue;
                }

                XaeroMapDataHandler.RegionCoord coord = new XaeroMapDataHandler.RegionCoord(
                        assembled.ref.regionX(), assembled.ref.regionZ(), assembled.ref.caveLayer());
                updatedRegionCoords.add(coord);

                boolean syncingCaveDimension = "minecraft:the_nether".equals(assembled.ref.dimId())
                        || "the_nether".equals(assembled.ref.dimId())
                        || "DIM-1".equals(assembled.ref.dimId());
                boolean shouldProcess = syncingCaveDimension ? !assembled.isSurfaceLayer() : assembled.isSurfaceLayer();

                syncPendingWrites.incrementAndGet();
                final int gen = generationAtEnqueue;
                final boolean processRegion = shouldProcess;

                ClientSyncWriteQueue.submit(assembled, serverDir, worldId, writeResult -> {
                    mc.execute(() -> {
                        try {
                            if (!session.isCurrent(gen)) {
                                return;
                            }

                            if (writeResult == null) {
                                LOGGER.error(
                                        "Region ({}, {}) write failed, skipping load ({} bytes)",
                                        assembled.ref.regionX(),
                                        assembled.ref.regionZ(),
                                        assembled.data.length);
                                syncFailed++;
                            } else if (processRegion && !session.reflectionFailed()) {
                                triggerSingleRegionLoad(coord);
                            }
                            LOGGER.debug(
                                    "[SYNC-WRITE] region=({},{}) layer={} result={} (writesBeforeDec={})",
                                    assembled.ref.regionX(),
                                    assembled.ref.regionZ(),
                                    assembled.ref.caveLayer(),
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
                LOGGER.debug(
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

        RegionRef ref = pendingRegionPaths.poll();
        if (ref == null) {
            LOGGER.debug(
                    "[SYNC] requestNextRegion: queue empty -> maybeCompleteSync (inFlight={}, writes={})",
                    regionRequestInFlight,
                    syncPendingWrites.get());
            maybeCompleteSync();
            return;
        }

        regionRequestInFlight = true;
        List<RegionRef> single = List.of(ref);
        MapSyncer.sendToServer(new MapRequestPayload(single));
        int seq = requestCounter.incrementAndGet();
        LOGGER.debug(
                "[SYNC] -> request #{}: {} (pendingLeft={}, syncProcessed={}/{})",
                seq,
                ref,
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
        LOGGER.debug("[SYNC-GUARD] completion guard cleared -> completeSync (partBufferKeys={})", partBuffer.size());
        completeSync();
    }

    private static void completeSync() {
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();

        int totalReceived = updatedRegionCoords.size();
        LOGGER.debug(
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

        clearSyncData();
        XaeroReflectionHelper.clearCache();
    }

    private static void finishUpToDate() {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(ChatUtils.desc("mapsyncer.command.no_regions"), false);
        }
        clearSyncData();
        XaeroReflectionHelper.clearCache();
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

    private static String partKey(RegionData chunk) {
        return chunk.ref.regionX() + "," + chunk.ref.regionZ() + "," + chunk.ref.dimId() + "," + chunk.ref.caveLayer();
    }

    private static @Nullable RegionData assemblePart(RegionData chunk) {
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
                RegionData[] arr = new RegionData[chunk.totalParts];
                arr[chunk.partIndex] = chunk;
                return new PartEntry(arr, now);
            }
            existing.parts()[chunk.partIndex] = chunk;
            return existing;
        });
        RegionData[] parts = entry.parts();
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

        for (RegionData p : parts) {
            if (p == null) return null;
        }

        LOGGER.info("[SYNC-PART] {} fully assembled ({} parts)", key, chunk.totalParts);
        partBuffer.remove(key);

        int totalLen = 0;
        for (RegionData p : parts) {
            totalLen += p.data.length;
        }
        byte[] assembled = new byte[totalLen];
        int offset = 0;
        for (RegionData p : parts) {
            System.arraycopy(p.data, 0, assembled, offset, p.data.length);
            offset += p.data.length;
        }

        RegionData first = parts[0];
        return new RegionData(first.ref, first.timestampMillis, assembled);
    }

    private static int countNonNull(RegionData[] parts) {
        int n = 0;
        for (RegionData p : parts) {
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
