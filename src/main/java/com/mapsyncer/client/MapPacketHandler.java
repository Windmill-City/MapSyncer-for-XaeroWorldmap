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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPacketHandler.class);

    private static final long PART_STALE_TIMEOUT_MS = 5 * 60 * 1000L;

    private static final Set<RegionRef> updatedRegionCoords = ConcurrentHashMap.newKeySet();

    private record PartEntry(RegionData[] parts, long firstArrivedMs) {}

    private static final ConcurrentHashMap<String, PartEntry> partBuffer = new ConcurrentHashMap<>();

    private static final ConcurrentLinkedQueue<RegionRef> pendingRegions = new ConcurrentLinkedQueue<>();

    private static volatile int syncTotal = 0;
    private static volatile int syncProcessed = 0;
    private static volatile int syncFailed = 0;
    private static final AtomicInteger syncPendingWrites = new AtomicInteger(0);

    private static volatile long syncStartMs = 0;

    private static final AtomicInteger requestCounter = new AtomicInteger(0);

    private static volatile boolean running = false;

    private static volatile ManifestPayload deferredManifest = null;

    public static void onDisconnect() {
        stop();
        LOGGER.info("Client disconnected, all resources cleaned up");
    }

    public static void handleSyncManifest(ManifestPayload payload, Supplier<NetworkEvent.Context> context) {
        LOGGER.info("[SYNC] <- manifest: entries={}", payload.timestamps().size());
        MapSyncer.enqueueWork(context, () -> {
            handleManifestReceived(payload);
        });
        context.get().setPacketHandled(true);
    }

    private static void handleManifestReceived(ManifestPayload payload) {
        if (!isWorldContextReady()) {
            LOGGER.info("Xaero map context not ready yet, deferring sync until Xaero assigns world id");
            deferredManifest = payload;
            return;
        }

        start();

        Map<RegionRef, Long> serverTimestamps = payload.timestamps();
        if (serverTimestamps.isEmpty()) {
            LOGGER.info("Server manifest is empty, nothing to sync");
            finishUpToDate();
            return;
        }

        ManifestClient.getManifestAsync(extractDimIds(serverTimestamps.keySet()), localMeta -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player == null) {
                    return;
                }

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

                pendingRegions.clear();
                pendingRegions.addAll(ordered);
                syncTotal = ordered.size();
                syncProcessed = 0;
                syncFailed = 0;
                LOGGER.debug("[SYNC] per-region pull started: {} regions to fetch", syncTotal);
                if (mc.player != null) {
                    mc.player.displayClientMessage(ChatUtils.message("mapsyncer.sync.started", syncTotal), false);
                }
                requestNextRegion();
            });
        });
    }

    private static void start() {
        running = true;
        syncStartMs = System.currentTimeMillis();
    }

    private static void stop() {
        running = false;
        pendingRegions.clear();
        deferredManifest = null;
        syncTotal = 0;
        syncProcessed = 0;
        syncFailed = 0;
        syncPendingWrites.set(0);
        updatedRegionCoords.clear();
        partBuffer.clear();
        requestCounter.set(0);
    }

    public static void onXaeroWorldContextReady() {
        if (deferredManifest == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            ManifestPayload manifest = deferredManifest;
            deferredManifest = null;
            if (manifest != null && isWorldContextReady()) {
                handleManifestReceived(manifest);
            } else if (manifest != null) {
                deferredManifest = manifest;
            }
        });
    }

    private static boolean isWorldContextReady() {
        String worldId = XaeroWorldMapBridge.getCurrentWorldId();
        return worldId != null && !worldId.isEmpty();
    }

    private static Set<String> extractDimIds(Set<RegionRef> keys) {
        Set<String> dimIds = new HashSet<>();
        for (RegionRef ref : keys) {
            dimIds.add(ref.dimId());
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
        MapSyncer.enqueueWork(context, () -> {
            LOGGER.debug(
                    "[SYNC] <- response: chunks={}, complete={} (receiving={}, pending={}, writes={}, partBufferKeys={})",
                    payload.chunks().size(),
                    payload.isComplete(),
                    running,
                    pendingRegions.size(),
                    syncPendingWrites.get(),
                    partBuffer.size());

            if (!running) {
                LOGGER.warn(
                        "[SYNC] ignoring response: not running (chunks={}, complete={})",
                        payload.chunks().size(),
                        payload.isComplete());
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            Path serverDir = XaeroWorldMapBridge.getCurrentServerDirectory();

            String worldId = XaeroWorldMapBridge.getCurrentWorldId();
            if (worldId == null || worldId.isEmpty()) {
                LOGGER.error(
                        "Unable to resolve current world id from Xaero, skipping {} received chunks",
                        payload.chunks().size());
                syncFailed += payload.chunks().size();
                if (payload.isComplete()) {
                    syncProcessed++;
                    requestNextRegion();
                }
                return;
            }

            if (serverDir == null) {
                LOGGER.error(
                        "Unable to resolve server directory, skipping {} received chunks",
                        payload.chunks().size());
                syncFailed += payload.chunks().size();
                if (payload.isComplete()) {
                    syncProcessed++;
                    requestNextRegion();
                }
                return;
            }

            for (RegionData chunk : payload.chunks()) {
                RegionData assembled = assemblePart(chunk);
                if (assembled == null) {
                    continue;
                }

                updatedRegionCoords.add(assembled.ref);

                syncPendingWrites.incrementAndGet();

                AsyncWriter.submit(assembled, success -> {
                    mc.execute(() -> {
                        try {
                            if (!success) {
                                LOGGER.error(
                                        "Region ({}, {}) write failed ({} bytes)",
                                        assembled.ref.regionX(),
                                        assembled.ref.regionZ(),
                                        assembled.data.length);
                                syncFailed++;
                            }
                            LOGGER.debug(
                                    "[SYNC-WRITE] region=({},{}) layer={} result={} (writesBeforeDec={})",
                                    assembled.ref.regionX(),
                                    assembled.ref.regionZ(),
                                    assembled.ref.caveLayer(),
                                    success ? "ok" : "FAILED",
                                    syncPendingWrites.get());
                        } finally {
                            syncPendingWrites.decrementAndGet();
                            maybeCompleteSync();
                        }
                    });
                });
            }

            if (payload.isComplete()) {
                syncProcessed++;
                LOGGER.debug(
                        "[SYNC] complete signal: syncProcessed={}/{} (pending={}, writes={}, partBufferKeys={})",
                        syncProcessed,
                        syncTotal,
                        pendingRegions.size(),
                        syncPendingWrites.get(),
                        partBuffer.size());
                requestNextRegion();
            }
        });
        context.get().setPacketHandled(true);
    }

    private static void requestNextRegion() {
        if (!running) return;

        RegionRef ref = pendingRegions.poll();
        if (ref == null) {
            LOGGER.debug(
                    "[SYNC] requestNextRegion: queue empty -> maybeCompleteSync (writes={})", syncPendingWrites.get());
            maybeCompleteSync();
            return;
        }

        List<RegionRef> single = List.of(ref);
        MapSyncer.sendToServer(new MapRequestPayload(single));
        int seq = requestCounter.incrementAndGet();
        LOGGER.debug(
                "[SYNC] -> request #{}: {} (pendingLeft={}, syncProcessed={}/{})",
                seq,
                ref,
                pendingRegions.size(),
                syncProcessed,
                syncTotal);
    }

    private static void maybeCompleteSync() {
        if (!running) {
            return;
        }
        int writes = syncPendingWrites.get();
        if (syncProcessed < syncTotal || writes > 0) {
            LOGGER.debug(
                    "[SYNC-GUARD] holding completion: syncProcessed={}/{} (pendingPaths={}), writes={}, partBufferKeys={}",
                    syncProcessed,
                    syncTotal,
                    pendingRegions.size(),
                    writes,
                    partBuffer.size());
            return;
        }
        LOGGER.debug("[SYNC-GUARD] completion guard cleared -> completeSync (partBufferKeys={})", partBuffer.size());
        completeSync();
    }

    private static void completeSync() {

        int totalReceived = updatedRegionCoords.size();
        LOGGER.debug(
                "Sync complete: {} regions processed (syncTotal={}, syncProcessed={}, syncFailed={}, uncompletedRequests={}, pending={}, writes={}, partBufferKeys={})",
                totalReceived,
                syncTotal,
                syncProcessed,
                syncFailed,
                syncTotal - syncProcessed,
                pendingRegions.size(),
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
                Minecraft.getInstance().player.displayClientMessage(ChatUtils.error("mapsyncer.sync.partial"), false);
            }
        }

        stop();
    }

    private static void finishUpToDate() {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(ChatUtils.desc("mapsyncer.command.no_regions"), false);
        }
        stop();
    }

    private static String partKey(RegionData chunk) {
        return chunk.ref.regionX() + "," + chunk.ref.regionZ() + "," + chunk.ref.dimId() + "," + chunk.ref.caveLayer();
    }

    private static RegionData assemblePart(RegionData chunk) {
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
}
