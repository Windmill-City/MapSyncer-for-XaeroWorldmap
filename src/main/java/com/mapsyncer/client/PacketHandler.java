package com.mapsyncer.client;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.ManifestPayload;
import com.mapsyncer.network.MapRequestPayload;
import com.mapsyncer.network.MapResponsePayload;
import com.mapsyncer.network.RegionData;
import com.mapsyncer.network.RegionRef;
import com.mapsyncer.util.ChatUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PacketHandler.class);

    private static final ConcurrentHashMap<String, RegionData[]> partBuffer = new ConcurrentHashMap<>();

    private static final ConcurrentLinkedQueue<RegionRef> pendingRegions = new ConcurrentLinkedQueue<>();

    private static volatile int syncTotal = 0;
    private static volatile int syncProcessed = 0;
    private static volatile int syncFailed = 0;

    private static volatile long syncStartMs = 0;

    private static volatile boolean running = false;

    private static volatile ManifestPayload deferredManifest = null;

    public static void onXaeroWorldContextReady() {
        if (deferredManifest == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            ManifestPayload manifest = deferredManifest;
            if (manifest != null && isWorldContextReady()) {
                deferredManifest = null;
                handleManifestReceived(manifest);
            }
        });
    }

    public static void onDisconnect() {
        stop();
    }

    public static void handleSyncManifest(ManifestPayload payload, Supplier<NetworkEvent.Context> context) {
        LOGGER.debug("[SYNC] <- manifest: entries={}", payload.timestamps().size());
        MapSyncer.enqueueWork(context, () -> handleManifestReceived(payload));
        context.get().setPacketHandled(true);
    }

    private static void handleManifestReceived(ManifestPayload payload) {
        if (!isWorldContextReady()) {
            LOGGER.debug("[SYNC] Xaero map context not ready yet, deferring sync until Xaero assigns world id");
            deferredManifest = payload;
            return;
        }

        Map<RegionRef, Long> serverTimestamps = payload.timestamps();
        if (serverTimestamps.isEmpty()) {
            LOGGER.debug("[SYNC] server manifest is empty, nothing to sync");
            finishUpToDate();
            return;
        }

        start();

        ManifestClient.getManifestAsync(extractDimIds(serverTimestamps.keySet()), localMeta -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player == null) {
                    return;
                }

                List<RegionRef> pending = collectPending(serverTimestamps, localMeta);
                if (pending.isEmpty()) {
                    LOGGER.debug("[SYNC] all {} server regions already up-to-date", serverTimestamps.size());
                    finishUpToDate();
                    return;
                }

                pending.sort(Comparator.comparingInt(ref -> ref.regionDistance(
                        mc.player.getBlockX() >> 9, mc.player.getBlockZ() >> 9)));

                pendingRegions.clear();
                pendingRegions.addAll(pending);
                syncTotal = pending.size();
                syncProcessed = 0;
                syncFailed = 0;
                mc.player.displayClientMessage(ChatUtils.message("mapsyncer.sync.started", syncTotal), false);
                LOGGER.debug("[SYNC] per-region pull started: {} regions to fetch", syncTotal);
                requestNextRegion();
            });
        });
    }

    private static List<RegionRef> collectPending(Map<RegionRef, Long> serverTimestamps, Map<RegionRef, Long> localMeta) {
        List<RegionRef> pending = new ArrayList<>();
        for (Map.Entry<RegionRef, Long> entry : serverTimestamps.entrySet()) {
            Long local = localMeta.get(entry.getKey());
            if (local == null || local < entry.getValue()) {
                pending.add(entry.getKey());
            }
        }
        return pending;
    }

    private static void start() {
        running = true;
        syncStartMs = System.currentTimeMillis();
        LOGGER.info("[SYNC] sync started");
    }

    private static void stop() {
        running = false;
        pendingRegions.clear();
        deferredManifest = null;
        syncTotal = 0;
        syncProcessed = 0;
        syncFailed = 0;
        partBuffer.clear();
        LOGGER.info("[SYNC] sync stopped, resources cleaned up");
    }

    private static boolean isWorldContextReady() {
        String worldId = XaeroBridge.getCurrentWorldId();
        return worldId != null && !worldId.isEmpty();
    }

    private static Set<String> extractDimIds(Set<RegionRef> keys) {
        Set<String> dimIds = new HashSet<>();
        for (RegionRef ref : keys) {
            dimIds.add(ref.dimId());
        }
        return dimIds;
    }

    public static void handleSyncResponse(MapResponsePayload payload, Supplier<NetworkEvent.Context> context) {
        MapSyncer.enqueueWork(context, () -> {
            if (!running) {
                LOGGER.warn("[SYNC] ignoring response: not running");
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (!isWorldContextReady()) {
                LOGGER.error(
                        "[SYNC] unable to resolve current world id from Xaero, skipping {} received chunks",
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

                AsyncWriter.submit(assembled, success -> mc.execute(() -> {
                    if (!success) {
                        LOGGER.error(
                                "[SYNC-WRITE] region ({}, {}) write failed ({} bytes)",
                                assembled.ref.regionX(),
                                assembled.ref.regionZ(),
                                assembled.data.length);
                        syncFailed++;
                    }
                    maybeCompleteSync();
                }));
            }

            if (payload.isComplete()) {
                syncProcessed++;
                LOGGER.debug("[SYNC] complete signal: syncProcessed={}/{}", syncProcessed, syncTotal);
                requestNextRegion();
            }
        });
        context.get().setPacketHandled(true);
    }

    private static void requestNextRegion() {
        if (!running) {
            return;
        }

        RegionRef ref = pendingRegions.poll();
        if (ref == null) {
            maybeCompleteSync();
            return;
        }

        MapSyncer.sendToServer(new MapRequestPayload(List.of(ref)));
        LOGGER.debug(
                "[SYNC] -> request: {} (pendingLeft={}, syncProcessed={}/{})",
                ref,
                pendingRegions.size(),
                syncProcessed,
                syncTotal);
    }

    private static void maybeCompleteSync() {
        if (!running) {
            return;
        }
        if (syncProcessed < syncTotal || AsyncWriter.hasPendingWrites()) {
            LOGGER.debug(
                    "[SYNC-GUARD] holding completion: syncProcessed={}/{} (pending={}), pendingWrites={}",
                    syncProcessed,
                    syncTotal,
                    pendingRegions.size(),
                    AsyncWriter.hasPendingWrites());
            return;
        }
        completeSync();
    }

    private static void completeSync() {
        int totalReceived = syncProcessed;
        LOGGER.debug("[SYNC] sync complete: {} regions processed, {} failed", syncProcessed, syncFailed);

        if (Minecraft.getInstance().player != null) {
            if (totalReceived > 0) {
                if (syncFailed > 0) {
                    Minecraft.getInstance().player.displayClientMessage(ChatUtils.error("mapsyncer.sync.partial"), false);
                }
                long elapsed = Math.max(0, (System.currentTimeMillis() - syncStartMs) / 1000);
                Minecraft.getInstance().player.displayClientMessage(
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
            LOGGER.warn("[SYNC-PART] invalid chunk part metadata: index={} total={}", chunk.partIndex, chunk.totalParts);
            return null;
        }

        String key = partKey(chunk);
        RegionData[] parts = partBuffer.computeIfAbsent(key, k -> new RegionData[chunk.totalParts]);
        parts[chunk.partIndex] = chunk;

        for (RegionData p : parts) {
            if (p == null) {
                return null;
            }
        }

        partBuffer.remove(key);
        LOGGER.debug("[SYNC-PART] {} fully assembled ({} parts)", key, chunk.totalParts);

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

        return new RegionData(parts[0].ref, parts[0].timestampMillis, assembled);
    }
}
