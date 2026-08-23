package com.mapsyncer.server;

import com.mapsyncer.network.impl.ForgeNetworkHandler;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.SyncManifestPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.util.PathMapping;
import com.mapsyncer.util.RegionMeta;
import java.io.BufferedReader;
import java.io.FileReader;
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
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerSyncHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSyncHandlerLogic.class);

    private static boolean warnedXaeromapFallback;

    private static boolean isManifestRequest(Map<String, RegionMeta> clientMeta) {
        return clientMeta == null || clientMeta.isEmpty();
    }

    private record RegionSyncInfo(
            Path zipPath,
            String normalizedPath,
            long timestampMillis,
            int regionX,
            int regionZ,
            String dimension,
            int caveLayer) {}

    public static void init() {
        ForgeNetworkHandler.registerSyncRequestHandler((payload, context) ->
                ForgeNetworkHandler.enqueueWork(context, () -> handleSyncRequest(payload, context)));
    }

    public static void pushManifestOnJoin(ServerPlayer player) {
        Path cacheDir = ConversionOrchestrator.getCacheDir();
        if (!Files.exists(cacheDir)) {
            LOGGER.debug("No cache dir, pushing no_cache manifest to player {}", player.getUUID());
            pushNoCacheManifest(player);
            return;
        }

        Path absCacheDir = cacheDir.toAbsolutePath().normalize();

        Map<String, Long> manifest = ManifestCache.get().build(absCacheDir);
        if (manifest.isEmpty()) {
            LOGGER.debug("Manifest is empty, pushing no_cache manifest to player {}", player.getUUID());
            pushNoCacheManifest(player);
            return;
        }

        int worldId = readWorldIdFromXaeroMap(player);
        for (SyncManifestPayload part : SyncManifestPayload.split(manifest, worldId, "ok")) {
            SyncTransferScheduler.enqueueManifest(player, part);
        }
        LOGGER.info("Proactively pushed sync manifest to player {}: {} regions", player.getUUID(), manifest.size());
    }

    private static void pushNoCacheManifest(ServerPlayer player) {
        int worldId = readWorldIdFromXaeroMap(player);
        ForgeNetworkHandler.sendToPlayer(player, new SyncManifestPayload(Map.of(), worldId, "no_cache"));
    }

    private static void handleSyncRequest(SyncRequestPayload payload, Supplier<NetworkEvent.Context> context) {
        Player player = ForgeNetworkHandler.getPlayerFromContext(context);
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        Map<String, RegionMeta> regionMeta = payload.regionMeta();

        LOGGER.info(
                "[SYNC-SRV] request from {}: metaEntries={}",
                serverPlayer.getName().getString(),
                regionMeta.size());

        Path cacheDir = ConversionOrchestrator.getCacheDir();
        if (!Files.exists(cacheDir)) {
            int worldId = readWorldIdFromXaeroMap(serverPlayer);
            ForgeNetworkHandler.sendToPlayer(serverPlayer, new SyncManifestPayload(Map.of(), worldId, "no_cache"));
            return;
        }

        Path absCacheDir = cacheDir.toAbsolutePath().normalize();

        if (isManifestRequest(regionMeta)) {
            sendManifest(serverPlayer, absCacheDir);
            return;
        }

        serveRequestedRegions(serverPlayer, regionMeta, absCacheDir);
    }

    private static void sendManifest(ServerPlayer player, Path absCacheDir) {
        int worldId = readWorldIdFromXaeroMap(player);
        Map<String, Long> manifest = ManifestCache.get().build(absCacheDir);

        if (manifest.isEmpty()) {
            ForgeNetworkHandler.sendToPlayer(player, new SyncManifestPayload(Map.of(), worldId, "no_cache"));
            return;
        }

        SyncManifestPayload[] parts = SyncManifestPayload.split(manifest, worldId, "ok");
        for (SyncManifestPayload part : parts) {
            SyncTransferScheduler.enqueueManifest(player, part);
        }
        LOGGER.info("Sync manifest sent to player {}: {} regions", player.getUUID(), manifest.size());
    }

    private static void serveRequestedRegions(
            ServerPlayer player, Map<String, RegionMeta> requested, Path absCacheDir) {
        ManifestCache manifestCache = ManifestCache.get();
        int worldId = readWorldIdFromXaeroMap(player);

        List<ChunkMapData> parts = new ArrayList<>();
        int failed = 0;

        for (String path : requested.keySet()) {
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
        SyncTransferScheduler.enqueueRegionResponse(player, parts, worldId, failed > 0 ? "partial" : "ok");
        SyncTransferScheduler.onRequestReceived(player);
    }

    private static int readWorldIdFromXaeroMap(ServerPlayer serverPlayer) {
        try {
            Path levelDataFile = serverPlayer.level().getServer().getWorldPath(LevelResource.LEVEL_DATA_FILE);
            Path worldRoot = levelDataFile.getParent();
            if (worldRoot == null) {
                return fallbackWorldId();
            }
            Path xaeromapPath = worldRoot.resolve("xaeromap.txt");

            if (!Files.exists(xaeromapPath)) {
                return fallbackWorldId();
            }

            try (BufferedReader reader = new BufferedReader(new FileReader(xaeromapPath.toFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":");
                    if (parts.length == 2 && parts[0].equals("id")) {
                        int worldId = Integer.parseInt(parts[1]);
                        LOGGER.info("Read worldId {} from xaeromap.txt", worldId);
                        return worldId;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read xaeromap.txt", e);
        }
        return 0;
    }

    private static int fallbackWorldId() {
        if (!warnedXaeromapFallback) {
            warnedXaeromapFallback = true;
            LOGGER.warn("xaeromap.txt not found; falling back to worldId 0. "
                    + "Install Xaero's World Map on the server to generate a proper xaeromap.txt");
        }
        return 0;
    }

    private static @Nullable RegionSyncInfo parseRegionInfo(
            Path zipPath, String normalizedPath, long timestampMillis) {
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

            return new RegionSyncInfo(
                    zipPath, normalizedPath, timestampMillis, regionX, regionZ, dimension, caveLayer);
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

    static String stripMwWorldId(String path) {
        String[] parts = path.split("/");
        if (parts.length >= 3 && parts[1].startsWith("mw$")) {
            StringBuilder sb = new StringBuilder(parts[0]);
            for (int i = 2; i < parts.length; i++) {
                sb.append("/").append(parts[i]);
            }
            return sb.toString();
        }
        return path;
    }

    static String toNormalizedServerPath(Path absCacheDir, Path zipPath) {
        String relativePath = absCacheDir.relativize(zipPath).toString();
        String normalizedPath = relativePath.replace(".zip", "").replace("\\", "/");
        normalizedPath = stripMwWorldId(normalizedPath);

        String[] parts = normalizedPath.split("[/\\\\]");
        String xaeroDimName = parts.length > 1 ? parts[0] : "unknown";

        String normalizedXaeroDim = PathMapping.toXaeroDimension(xaeroDimName);
        if (!normalizedXaeroDim.equals(xaeroDimName)) {
            normalizedPath = normalizedXaeroDim + normalizedPath.substring(xaeroDimName.length());
        }
        return normalizedPath;
    }
}
