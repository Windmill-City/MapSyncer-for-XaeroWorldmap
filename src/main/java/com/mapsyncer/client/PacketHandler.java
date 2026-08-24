package com.mapsyncer.client;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.ManifestPayload;
import com.mapsyncer.network.MapRequestPayload;
import com.mapsyncer.network.MapResponsePayload;
import com.mapsyncer.network.RegionData;
import com.mapsyncer.network.RegionRef;
import com.mapsyncer.util.ChatUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PacketHandler.class);

    private static final ArrayDeque<RegionRef> pendingRegions = new ArrayDeque<>();

    private static int syncTotal = 0;
    private static int syncProcessed = 0;
    private static int syncFailed = 0;
    private static long syncStartMs = 0;

    private static boolean running = false;

    private static ManifestPayload deferredManifest = null;

    public static void onDisconnect() {
        stop();
    }

    public static void onXaeroWorldContextReady() {
        Minecraft.getInstance().execute(() -> {
            if (deferredManifest != null) {
                handleManifestReceived(deferredManifest);
            }
        });
    }

    public static void handleSyncManifest(ManifestPayload payload, Supplier<NetworkEvent.Context> context) {
        LOGGER.debug("[SYNC] <- manifest: entries={}", payload.timestamps().size());
        Minecraft.getInstance().execute(() -> handleManifestReceived(payload));
        context.get().setPacketHandled(true);
    }

    public static void handleSyncResponse(MapResponsePayload payload, Supplier<NetworkEvent.Context> context) {
        Minecraft.getInstance().execute(() -> {
            RegionData chunk = payload.chunk();
            if (chunk != null) {
                AsyncWriter.submit(chunk, success -> Minecraft.getInstance().execute(() -> {
                    if (!success) {
                        LOGGER.error(
                                "[SYNC-WRITE] region ({}, {}) write failed ({} bytes)",
                                chunk.ref.regionX(),
                                chunk.ref.regionZ(),
                                chunk.data.length);
                        syncFailed++;
                    }
                    maybeCompleteSync();
                }));
            } else {
                LOGGER.debug("[SYNC] region missing on server, skipping");
            }

            syncProcessed++;
            LOGGER.debug("[SYNC] region data received: syncProcessed={}/{}", syncProcessed, syncTotal);
            requestNextRegion();
        });
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

        if (running) {
            LOGGER.debug("[SYNC] sync already started, skipping...");
            return;
        }

        running = true;
        syncStartMs = System.currentTimeMillis();
        LOGGER.info("[SYNC] sync started");

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

    private static List<RegionRef> collectPending(Map<RegionRef, Long> serverTimestamps,
            Map<RegionRef, Long> localMeta) {
        List<RegionRef> pending = new ArrayList<>();
        for (Map.Entry<RegionRef, Long> entry : serverTimestamps.entrySet()) {
            Long local = localMeta.get(entry.getKey());
            if (local == null || local < entry.getValue()) {
                pending.add(entry.getKey());
            }
        }
        return pending;
    }

    private static void stop() {
        running = false;
        pendingRegions.clear();
        deferredManifest = null;
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

    private static void requestNextRegion() {
        RegionRef ref = pendingRegions.poll();
        if (ref == null) {
            return;
        }

        MapSyncer.sendToServer(new MapRequestPayload(ref));
        LOGGER.debug(
                "[SYNC] -> request: {} (pendingLeft={}, syncProcessed={}/{})",
                ref,
                pendingRegions.size(),
                syncProcessed,
                syncTotal);
    }

    private static void maybeCompleteSync() {
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
        LOGGER.debug("[SYNC] sync complete: {} regions processed, {} failed", syncProcessed, syncFailed);

        if (Minecraft.getInstance().player != null) {
            long elapsed = Math.max(0, (System.currentTimeMillis() - syncStartMs) / 1000);
            Minecraft.getInstance().player.displayClientMessage(
                    ChatUtils.success("mapsyncer.sync.completed", syncProcessed, syncFailed, elapsed), false);
        }

        stop();
    }

    private static void finishUpToDate() {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(ChatUtils.desc("mapsyncer.command.no_regions"), false);
        }
    }
}
