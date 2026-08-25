package com.mapsyncer.server;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.mca.RegionData;
import com.mapsyncer.mca.RegionRef;
import com.mapsyncer.network.ManifestPayload;
import com.mapsyncer.network.RequestPayload;
import com.mapsyncer.network.ResponsePayload;
import com.mapsyncer.util.PathUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PacketHandler {

    private static final Logger LOGGER = LogManager.getLogger(PacketHandler.class);

    public static void pushManifest(ServerPlayer player) {
        Map<RegionRef, Long> manifest = ManifestServer.get(player.server);
        MapSyncer.sendToPlayer(player, new ManifestPayload(manifest));
        LOGGER.debug("Proactively pushed sync manifest to player {}: {} regions", player.getUUID(), manifest.size());
    }

    public static void handleRequest(RequestPayload payload, Supplier<NetworkEvent.Context> context) {
        MapSyncer.enqueueWork(context, () -> {
            Player player = MapSyncer.getPlayerFromContext(context);
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            RegionRef requested = payload.region();

            LOGGER.debug(
                    "[SYNC-SRV] request from {}: region={}",
                    serverPlayer.getName().getString(),
                    requested);

            serveRequestedRegion(serverPlayer, requested);
        });
        context.get().setPacketHandled(true);
    }

    private static void serveRequestedRegion(ServerPlayer player, RegionRef region) {
        RegionData chunk = getRegionData(region);
        LOGGER.debug(
                "[SYNC-SRV] send to {}: region {}, {} bytes",
                player.getName().getString(),
                region,
                chunk == null ? 0 : chunk.data.length);
        MapSyncer.sendToPlayer(player, new ResponsePayload(chunk));
    }

    private static RegionData getRegionData(RegionRef region) {
        Path zipPath = resolveZipPath(region);
        try {
            byte[] data = Files.readAllBytes(zipPath);
            return new RegionData(region, data);
        } catch (IOException e) {
            LOGGER.error("Failed to read zip file: {}", zipPath, e);
            return null;
        }
    }

    private static Path resolveZipPath(RegionRef ref) {
        Path dimDir = PathUtils.getDimDirServer(ref.dimId());
        Path dstDir = ref.isSurface() ? dimDir : dimDir.resolve("caves").resolve(String.valueOf(ref.cave()));
        return dstDir.resolve(ref.X() + "_" + ref.Z() + ".zip");
    }
}
