package com.mapsyncer.server;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.ManifestPayload;
import com.mapsyncer.network.MapRequestPayload;
import com.mapsyncer.network.MapResponsePayload;
import com.mapsyncer.network.RegionData;
import com.mapsyncer.network.RegionRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PacketHandler.class);

    public static void pushManifest(ServerPlayer player) {
        Map<RegionRef, Long> manifest = ManifestServer.build(player.server);
        MapSyncer.sendToPlayer(player, new ManifestPayload(manifest));
        LOGGER.debug("Proactively pushed sync manifest to player {}: {} regions", player.getUUID(), manifest.size());
    }

    public static void handleMapRequest(MapRequestPayload payload, Supplier<NetworkEvent.Context> context) {
        MapSyncer.enqueueWork(context, () -> {
            Player player = MapSyncer.getPlayerFromContext(context);
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            RegionRef requested = payload.region();

            LOGGER.debug(
                    "[SYNC-SRV] request from {}: region={}",
                    serverPlayer.getName().getString(),
                    requested);

            Path cacheDir = MapSyncer.CACHE_DIR;
            if (!Files.exists(cacheDir)) return;

            ManifestServer.build(serverPlayer.server);

            serveRequestedRegion(serverPlayer, requested);
        });
        context.get().setPacketHandled(true);
    }

    private static void serveRequestedRegion(ServerPlayer player, RegionRef region) {
        Path zipPath = ManifestServer.resolveZipPath(region);
        Long timestamp = ManifestServer.getTimestamp(region);
        if (zipPath == null || timestamp == null || !Files.isRegularFile(zipPath)) {
            LOGGER.warn("Requested region not found or invalid: {}", region);
            MapSyncer.sendToPlayer(player, new MapResponsePayload(null));
            return;
        }
        RegionData chunk = readRegionData(zipPath, timestamp, region);
        if (chunk == null) {
            MapSyncer.sendToPlayer(player, new MapResponsePayload(null));
            return;
        }
        sendRegionResponse(player, chunk);
    }

    private static RegionData readRegionData(Path zipPath, long timestampMillis, RegionRef region) {
        try {
            byte[] data = Files.readAllBytes(zipPath);
            return new RegionData(region, timestampMillis, data);
        } catch (IOException e) {
            LOGGER.error("Failed to read zip file: {}", zipPath, e);
            return null;
        }
    }

    private static void sendRegionResponse(ServerPlayer player, RegionData chunk) {
        LOGGER.debug(
                "[SYNC-SRV] send to {}: region {}, {} bytes",
                player.getName().getString(),
                chunk.ref,
                chunk.data.length);
        MapSyncer.sendToPlayer(player, new MapResponsePayload(chunk));
    }
}
