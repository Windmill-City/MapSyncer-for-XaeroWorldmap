package com.mapsyncer.server;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.impl.NetworkHandler;
import com.mapsyncer.network.payload.ChunkMapData;
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

    public static void init() {
        NetworkHandler.registerSyncRequestHandler(
                (payload, context) -> NetworkHandler.enqueueWork(context, () -> handleSyncRequest(payload, context)));
    }

    public static void pushManifestOnJoin(ServerPlayer player) {
        Map<RegionRef, Long> manifest = ManifestServer.get().build(player.server);
        NetworkHandler.sendToPlayer(player, new SyncManifestPayload(manifest));
        LOGGER.info("Proactively pushed sync manifest to player {}: {} regions", player.getUUID(), manifest.size());
    }

    private static void handleSyncRequest(SyncRequestPayload payload, Supplier<NetworkEvent.Context> context) {
        Player player = NetworkHandler.getPlayerFromContext(context);
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        List<RegionRef> requested = payload.regions();

        LOGGER.info(
                "[SYNC-SRV] request from {}: regions={}", serverPlayer.getName().getString(), requested.size());

        Path cacheDir = MapSyncer.CACHE_DIR;
        if (!Files.exists(cacheDir)) return;

        ManifestServer.get().build(serverPlayer.server);

        serveRequestedRegions(serverPlayer, requested);
    }

    private static void serveRequestedRegions(ServerPlayer player, List<RegionRef> requested) {
        ManifestServer manifestCache = ManifestServer.get();

        List<ChunkMapData> parts = new ArrayList<>();
        int failed = 0;

        for (RegionRef region : requested) {
            Path zipPath = manifestCache.resolveZipPath(region);
            Long timestamp = manifestCache.getTimestamp(region);
            if (zipPath == null || timestamp == null || !Files.isRegularFile(zipPath)) {
                failed++;
                LOGGER.warn("Requested region not found or invalid: {}", region);
                continue;
            }
            ChunkMapData chunk = readRegionData(zipPath, timestamp, region);
            if (chunk == null) {
                failed++;
                continue;
            }
            for (ChunkMapData part : ChunkMapData.split(chunk)) {
                parts.add(part);
            }
        }

        LOGGER.info(
                "[SYNC-SRV] serving {} requested regions for {}: produced {} parts, {} failed",
                requested.size(),
                player.getName().getString(),
                parts.size(),
                failed);
        sendRegionResponse(player, parts, failed > 0 ? "partial" : "ok");
    }

    private static @Nullable ChunkMapData readRegionData(Path zipPath, long timestampMillis, RegionRef region) {
        try {
            byte[] data = Files.readAllBytes(zipPath);
            return new ChunkMapData(
                    region.regionX(), region.regionZ(), region.dimId(), data, timestampMillis, region.caveLayer());
        } catch (IOException e) {
            LOGGER.error("Failed to read zip file: {}", zipPath, e);
            return null;
        }
    }

    private static void sendRegionResponse(ServerPlayer player, List<ChunkMapData> parts, String status) {
        if (parts.isEmpty()) {
            NetworkHandler.sendToPlayer(player, new SyncResponsePayload(List.of(), true, status));
            return;
        }
        List<ChunkMapData> batch = new ArrayList<>();
        int batchBytes = 0;
        for (ChunkMapData part : parts) {
            if (!batch.isEmpty() && batchBytes + part.data.length > MAX_RESPONSE_PACKET_BYTES) {
                sendRegionBatch(player, batch, status, false);
                batch = new ArrayList<>();
                batchBytes = 0;
            }
            batch.add(part);
            batchBytes += part.data.length;
        }
        sendRegionBatch(player, batch, status, true);
    }

    private static void sendRegionBatch(
            ServerPlayer player, List<ChunkMapData> batch, String status, boolean complete) {
        int bytes = 0;
        for (ChunkMapData part : batch) bytes += part.data.length;
        LOGGER.info(
                "[SYNC-SRV] send to {}: {} parts, {} bytes, complete={}",
                player.getName().getString(),
                batch.size(),
                bytes,
                complete);
        NetworkHandler.sendToPlayer(player, new SyncResponsePayload(batch, complete, status));
    }
}
