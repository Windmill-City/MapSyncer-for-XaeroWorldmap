package com.mapsyncer.server;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.payload.RegionData;
import com.mapsyncer.network.payload.RegionRef;
import com.mapsyncer.network.payload.SyncManifestPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerSyncHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSyncHandlerLogic.class);

    private static final int MAX_RESPONSE_PACKET_BYTES = 256 * 1024;

    public static void pushManifestOnJoin(ServerPlayer player) {
        Map<RegionRef, Long> manifest = ManifestServer.get().build(player.server);
        MapSyncer.sendToPlayer(player, new SyncManifestPayload(manifest));
        LOGGER.info("Proactively pushed sync manifest to player {}: {} regions", player.getUUID(), manifest.size());
    }

    public static void handleSyncRequest(SyncRequestPayload payload, Supplier<NetworkEvent.Context> context) {
        MapSyncer.enqueueWork(context, () -> {
            Player player = MapSyncer.getPlayerFromContext(context);
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            List<RegionRef> requested = payload.regions();

            LOGGER.info(
                    "[SYNC-SRV] request from {}: regions={}", serverPlayer.getName().getString(), requested.size());

            Path cacheDir = MapSyncer.CACHE_DIR;
            if (!Files.exists(cacheDir)) return;

            ManifestServer.get().build(serverPlayer.server);

            serveRequestedRegions(serverPlayer, requested);
        });
    }

    private static void serveRequestedRegions(ServerPlayer player, List<RegionRef> requested) {
        ManifestServer manifestCache = ManifestServer.get();

        List<RegionData> parts = new ArrayList<>();
        int failed = 0;

        for (RegionRef region : requested) {
            Path zipPath = manifestCache.resolveZipPath(region);
            Long timestamp = manifestCache.getTimestamp(region);
            if (zipPath == null || timestamp == null || !Files.isRegularFile(zipPath)) {
                failed++;
                LOGGER.warn("Requested region not found or invalid: {}", region);
                continue;
            }
            RegionData chunk = readRegionData(zipPath, timestamp, region);
            if (chunk == null) {
                failed++;
                continue;
            }
            for (RegionData part : RegionData.split(chunk)) {
                parts.add(part);
            }
        }

        LOGGER.info(
                "[SYNC-SRV] serving {} requested regions for {}: produced {} parts, {} failed",
                requested.size(),
                player.getName().getString(),
                parts.size(),
                failed);
        sendRegionResponse(player, parts);
    }

    private static @Nullable RegionData readRegionData(Path zipPath, long timestampMillis, RegionRef region) {
        try {
            byte[] data = Files.readAllBytes(zipPath);
            return new RegionData(region, timestampMillis, data);
        } catch (IOException e) {
            LOGGER.error("Failed to read zip file: {}", zipPath, e);
            return null;
        }
    }

    private static void sendRegionResponse(ServerPlayer player, List<RegionData> parts) {
        if (parts.isEmpty()) {
            MapSyncer.sendToPlayer(player, new SyncResponsePayload(List.of(), true));
            return;
        }
        List<RegionData> batch = new ArrayList<>();
        int batchBytes = 0;
        for (RegionData part : parts) {
            if (!batch.isEmpty() && batchBytes + part.data.length > MAX_RESPONSE_PACKET_BYTES) {
                sendRegionBatch(player, batch, false);
                batch = new ArrayList<>();
                batchBytes = 0;
            }
            batch.add(part);
            batchBytes += part.data.length;
        }
        sendRegionBatch(player, batch, true);
    }

    private static void sendRegionBatch(ServerPlayer player, List<RegionData> batch, boolean complete) {
        int bytes = 0;
        for (RegionData part : batch) bytes += part.data.length;
        LOGGER.info(
                "[SYNC-SRV] send to {}: {} parts, {} bytes, complete={}",
                player.getName().getString(),
                batch.size(),
                bytes,
                complete);
        MapSyncer.sendToPlayer(player, new SyncResponsePayload(batch, complete));
    }
}
