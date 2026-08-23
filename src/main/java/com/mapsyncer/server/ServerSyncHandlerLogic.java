package com.mapsyncer.server;

import com.mapsyncer.network.impl.NetworkHandler;
import com.mapsyncer.network.payload.ChunkMapData;
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

    private record RegionSyncInfo(
            Path zipPath,
            String normalizedPath,
            long timestampMillis,
            int regionX,
            int regionZ,
            String dimension,
            int caveLayer) {}

    public static void init() {
        NetworkHandler.registerSyncRequestHandler(
                (payload, context) -> NetworkHandler.enqueueWork(context, () -> handleSyncRequest(payload, context)));
    }

    public static void pushManifestOnJoin(ServerPlayer player) {
        Path absCacheDir = ConversionOrchestrator.getCacheDir().toAbsolutePath().normalize();
        Map<String, Long> manifest = ManifestServer.get().build(absCacheDir);
        for (SyncManifestPayload part : SyncManifestPayload.split(manifest)) {
            NetworkHandler.sendToPlayer(player, part);
        }
        LOGGER.info("Proactively pushed sync manifest to player {}: {} regions", player.getUUID(), manifest.size());
    }

    private static void handleSyncRequest(SyncRequestPayload payload, Supplier<NetworkEvent.Context> context) {
        Player player = NetworkHandler.getPlayerFromContext(context);
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        List<String> requested = payload.paths();

        LOGGER.info(
                "[SYNC-SRV] request from {}: regions={}", serverPlayer.getName().getString(), requested.size());

        Path cacheDir = ConversionOrchestrator.getCacheDir();
        if (!Files.exists(cacheDir)) return;

        Path absCacheDir = cacheDir.toAbsolutePath().normalize();

        serveRequestedRegions(serverPlayer, requested, absCacheDir);
    }

    private static void serveRequestedRegions(ServerPlayer player, List<String> requested, Path absCacheDir) {
        ManifestServer manifestCache = ManifestServer.get();

        List<ChunkMapData> parts = new ArrayList<>();
        int failed = 0;

        for (String path : requested) {
            Path zipPath = manifestCache.resolveZipPath(path);
            Long timestamp = manifestCache.getTimestamp(path);
            if (zipPath == null || timestamp == null || !Files.isRegularFile(zipPath)) {
                failed++;
                LOGGER.warn("Requested region not found or invalid: {}", path);
                continue;
            }
            RegionSyncInfo info = parseRegionInfo(zipPath, path, timestamp);
            if (info == null) {
                failed++;
                continue;
            }
            ChunkMapData chunk = readRegionData(info);
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

    private static @Nullable RegionSyncInfo parseRegionInfo(Path zipPath, String normalizedPath, long timestampMillis) {
        try {
            String[] parts = normalizedPath.split("[/\\\\]");

            String dimension;
            int caveLayer = Integer.MAX_VALUE;
            String fileName;

            if (parts.length >= 4 && parts[1].equals("caves")) {
                dimension = parts[0];
                caveLayer = Integer.parseInt(parts[2]);
                fileName = parts[3];
            } else {
                dimension = parts[0];
                fileName = parts[parts.length - 1];
            }

            String[] coords = fileName.split("_");
            int regionX = Integer.parseInt(coords[0]);
            int regionZ = Integer.parseInt(coords[1]);

            return new RegionSyncInfo(zipPath, normalizedPath, timestampMillis, regionX, regionZ, dimension, caveLayer);
        } catch (NumberFormatException e) {
            LOGGER.error("Failed to parse path: {}", normalizedPath, e);
            return null;
        }
    }

    private static @Nullable ChunkMapData readRegionData(RegionSyncInfo info) {
        try {
            byte[] data = Files.readAllBytes(info.zipPath());
            return new ChunkMapData(
                    info.regionX(), info.regionZ(), info.dimension(), data, info.timestampMillis(), info.caveLayer());
        } catch (IOException e) {
            LOGGER.error("Failed to read zip file: {}", info.zipPath(), e);
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
