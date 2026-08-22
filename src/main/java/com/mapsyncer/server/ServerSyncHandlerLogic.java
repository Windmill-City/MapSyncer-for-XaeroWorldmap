package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.impl.ForgeNetworkHandler;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.SyncManifestPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import com.mapsyncer.util.ApiHelper;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.ClientMeta;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class ServerSyncHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSyncHandlerLogic.class);

    private static final int MAX_PACKET_SIZE_LIMIT = 1_000_000;

    private static int getMaxPacketSize() {
        int configValue = ModConfig.SERVER.maxSyncPacketSize.get();
        return Math.min(configValue, MAX_PACKET_SIZE_LIMIT);
    }

    private static boolean isManifestRequest(Map<String, ClientMeta> clientMeta) {
        return clientMeta == null || clientMeta.isEmpty();
    }

    private record RegionSyncInfo(Path zipPath, String normalizedPath, long timestampSeconds,
                                   int regionX, int regionZ, String dimension, int caveLayer) {
    }

    public static void registerHandlers() {
        ForgeNetworkHandler.get().registerSyncRequestHandler(
            (payload, context) -> ForgeNetworkHandler.enqueueWork(context, () -> handleSyncRequest(payload, context))
        );
    }

    public static void pushManifestOnJoin(ServerPlayer player) {
        Path cacheDir = ConversionOrchestrator.getCacheDir();
        if (!Files.exists(cacheDir)) {
            LOGGER.debug("No cache dir, skipping proactive manifest push for player {}", player.getUUID());
            return;
        }

        Path absCacheDir = cacheDir.toAbsolutePath().normalize();
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        GenerationCache genCache = GenerationCache.getInstance(cacheDir);

        Set<String> dimensions = discoverDimensionsFromCache(absCacheDir);
        if (dimensions.isEmpty()) {
            LOGGER.debug("No cached dimensions, skipping proactive manifest push for player {}", player.getUUID());
            return;
        }

        Map<String, Long> manifest = ManifestCache.getInstance().buildManifest(
                absCacheDir, dimensions, dimMapping, genCache);
        genCache.save();
        if (manifest.isEmpty()) {
            LOGGER.debug("Manifest is empty, skipping proactive manifest push for player {}", player.getUUID());
            return;
        }

        ForgeNetworkHandler.confirmPlayer(player.getUUID());
        int worldId = readWorldIdFromXaeroMap(player);
        for (SyncManifestPayload part : SyncManifestPayload.split(manifest, worldId, "ok")) {
            ForgeNetworkHandler.get().sendToPlayer(player, part);
        }
        LOGGER.info("Proactively pushed sync manifest to player {}: {} regions", player.getUUID(), manifest.size());
    }

    private static void handleSyncRequest(SyncRequestPayload payload, Supplier<NetworkEvent.Context> context) {
        Player player = ForgeNetworkHandler.getPlayerFromContext(context);
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        Map<String, ClientMeta> clientMeta = payload.clientMeta();
        boolean syncAll = payload.syncAll();
        String targetDimension = payload.targetDimension();
        boolean silent = payload.silent();

        Path cacheDir = ConversionOrchestrator.getCacheDir();
        if (!Files.exists(cacheDir)) {
            int worldId = readWorldIdFromXaeroMap(serverPlayer);
            if (!silent) {
                serverPlayer.sendSystemMessage(ChatUtils.message(
                        "mapsyncer.server.no_cache", CacheCommandHandler.serverCommandPrefix()));
            }
            ForgeNetworkHandler.get().sendToPlayer(serverPlayer,
                    new SyncManifestPayload(Map.of(), worldId, "no_cache"));
            return;
        }

        Path absCacheDir = cacheDir.toAbsolutePath().normalize();
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        GenerationCache genCache = GenerationCache.getInstance(cacheDir);

        if (isManifestRequest(clientMeta)) {
            sendManifest(serverPlayer, syncAll, targetDimension, silent, absCacheDir, dimMapping, genCache);
            return;
        }

        serveRequestedRegions(serverPlayer, clientMeta, absCacheDir, dimMapping);
    }

    private static void sendManifest(ServerPlayer player, boolean syncAll, String targetDimension, boolean silent,
            Path absCacheDir, DimensionPathMapping dimMapping, GenerationCache genCache) {
        int worldId = readWorldIdFromXaeroMap(player);

        Set<String> requestedDimensions = new HashSet<>();
        if (syncAll) {
            requestedDimensions.addAll(discoverDimensionsFromCache(absCacheDir));
            LOGGER.info("Sync-all: discovered {} dimensions from cache", requestedDimensions.size());
        } else if (targetDimension != null && !targetDimension.isEmpty()) {
            requestedDimensions.add(targetDimension);
            LOGGER.debug("Single-dimension sync: {}", targetDimension);
        } else {
            requestedDimensions.add(dimMapping.toXaeroDimension(ApiHelper.getDimId(player.level().dimension())));
        }

        Map<String, Long> manifest = ManifestCache.getInstance().buildManifest(
                absCacheDir, requestedDimensions, dimMapping, genCache);
        genCache.save();

        if (manifest.isEmpty()) {
            if (!silent) {
                if (targetDimension != null && !targetDimension.isEmpty()) {
                    String friendlyDim = dimMapping.toServerDimension(targetDimension);
                    player.sendSystemMessage(ChatUtils.error(
                            "mapsyncer.server.dim_not_available",
                            friendlyDim,
                            CacheCommandHandler.serverCommandPrefix(),
                            friendlyDim));
                } else {
                    player.sendSystemMessage(ChatUtils.message(
                            "mapsyncer.server.no_cache", CacheCommandHandler.serverCommandPrefix()));
                }
            }
            ForgeNetworkHandler.get().sendToPlayer(player,
                    new SyncManifestPayload(Map.of(), worldId, "dim_not_available"));
            return;
        }

        SyncManifestPayload[] parts = SyncManifestPayload.split(manifest, worldId, "ok");
        if (!silent) {
            player.sendSystemMessage(ChatUtils.message("mapsyncer.server.manifest_ready", manifest.size()));
        }
        for (SyncManifestPayload part : parts) {
            ForgeNetworkHandler.get().sendToPlayer(player, part);
        }
        LOGGER.info("Sync manifest sent to player {}: {} regions", player.getUUID(), manifest.size());
    }

    private static void serveRequestedRegions(ServerPlayer player, Map<String, ClientMeta> requested,
            Path absCacheDir, DimensionPathMapping dimMapping) {
        ManifestCache manifestCache = ManifestCache.getInstance();
        int worldId = readWorldIdFromXaeroMap(player);

        List<ChunkMapData> chunks = new ArrayList<>();
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
                chunks.add(part);
            }
        }

        sendChunks(player, worldId, chunks, failed > 0 ? "partial" : "ok");
    }

    private static void sendChunks(ServerPlayer player, int worldId, List<ChunkMapData> chunks, String status) {
        int maxPacketSize = getMaxPacketSize();
        List<ChunkMapData> batch = new ArrayList<>();
        int batchBytes = 0;

        for (ChunkMapData chunk : chunks) {
            if (batchBytes + chunk.data.length > maxPacketSize && !batch.isEmpty()) {
                ForgeNetworkHandler.get().sendToPlayer(player,
                        new SyncResponsePayload(new ArrayList<>(batch), false, worldId, "ok"));
                batch.clear();
                batchBytes = 0;
            }
            batch.add(chunk);
            batchBytes += chunk.data.length;
        }

        if (!batch.isEmpty()) {
            ForgeNetworkHandler.get().sendToPlayer(player,
                    new SyncResponsePayload(new ArrayList<>(batch), true, worldId, status));
        } else {
            ForgeNetworkHandler.get().sendToPlayer(player,
                    new SyncResponsePayload(List.of(), true, worldId, status));
        }
    }

    private static Set<String> discoverDimensionsFromCache(Path cacheDir) {
        Set<String> dims = new HashSet<>();
        if (!Files.exists(cacheDir)) {
            return dims;
        }
        try (Stream<Path> topLevel = Files.list(cacheDir)) {
            topLevel.filter(Files::isDirectory).forEach(dimDir -> {
                String xaeroDim = dimDir.getFileName().toString();
                try (Stream<Path> stream = Files.walk(dimDir)) {
                    if (stream.anyMatch(p -> p.toString().endsWith(".zip"))) {
                        dims.add(xaeroDim);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to walk dimension {} cache", xaeroDim, e);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to list cache directory", e);
        }
        return dims;
    }

    private static int readWorldIdFromXaeroMap(ServerPlayer serverPlayer) {
        try {
            Path xaeromapPath = serverPlayer.level().getServer()
                    .getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent()
                    .resolve("xaeromap.txt");

            if (!Files.exists(xaeromapPath)) {
                LOGGER.warn("xaeromap.txt not found at {}", xaeromapPath);
                return 0;
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

    private static RegionSyncInfo parseRegionInfo(Path zipPath, String normalizedPath, long timestampSeconds) {
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

            return new RegionSyncInfo(zipPath, normalizedPath, timestampSeconds, regionX, regionZ, dimension, caveLayer);
        } catch (NumberFormatException e) {
            LOGGER.error("Failed to parse path: {}", normalizedPath, e);
            return null;
        }
    }

    private static ChunkMapData readRegionData(RegionSyncInfo info) {
        try {
            byte[] data = Files.readAllBytes(info.zipPath());
            return new ChunkMapData(info.regionX(), info.regionZ(), info.dimension(),
                    data, info.timestampSeconds(), info.caveLayer());
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

    static String toNormalizedServerPath(Path absCacheDir, Path zipPath, DimensionPathMapping dimMapping) {
        String relativePath = absCacheDir.relativize(zipPath).toString();
        String normalizedPath = relativePath.replace(".zip", "").replace("\\", "/");
        normalizedPath = stripMwWorldId(normalizedPath);

        String[] parts = normalizedPath.split("[/\\\\]");
        String xaeroDimName = parts.length > 1 ? parts[0] : "unknown";

        String normalizedXaeroDim = dimMapping.toXaeroDimension(xaeroDimName);
        if (!normalizedXaeroDim.equals(xaeroDimName)) {
            normalizedPath = normalizedXaeroDim + normalizedPath.substring(xaeroDimName.length());
        }
        return normalizedPath;
    }
}
