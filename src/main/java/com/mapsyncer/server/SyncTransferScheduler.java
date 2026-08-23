package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.impl.ForgeNetworkHandler;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.SyncManifestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SyncTransferScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncTransferScheduler.class);

    private static final long HARD_MIN_BYTES_PER_SECOND = 64 * 1024;

    private static final long HARD_MAX_BYTES_PER_SECOND = 8L * 1024 * 1024;

    private static final double MAX_ACCUMULATED_BUDGET_BYTES = 1024 * 1024;

    private static final long PING_TARGET_MAX_MS = 50;

    private static final long PING_TARGET_MIN_MS = 500;

    private static final double PING_ADAPT_FACTOR = 0.15;

    private static final long RTT_HIGH_MS = 1500;

    private static final double RTT_SLOW_FACTOR = 0.75;

    private static final long STRUGGLE_SUPPRESS_MS = 5000;

    private static final double MAX_DT_SECONDS = 5.0;

    private static final Map<UUID, PlayerQueue> queues = new ConcurrentHashMap<>();

    private static final class PendingResponse {
        final List<ChunkMapData> parts;
        final int worldId;
        final String status;
        int cursor;

        PendingResponse(List<ChunkMapData> parts, int worldId, String status) {
            this.parts = parts;
            this.worldId = worldId;
            this.status = status;
        }

        int remaining() {
            return parts.size() - cursor;
        }
    }

    private static final class PlayerQueue {
        final UUID playerId;
        final ConcurrentLinkedQueue<PendingResponse> responses = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<SyncManifestPayload> manifests = new ConcurrentLinkedQueue<>();
        volatile long rateBytesPerSecond;
        volatile long lastCompleteSentNs = 0;
        volatile long suppressUntilNs = 0;
        volatile long pingTargetBytesPerSecond = 0;
        volatile double budget = 0;
        volatile long lastTickNs = 0;

        PlayerQueue(UUID playerId) {
            this.playerId = playerId;
            this.rateBytesPerSecond = ModConfig.SERVER.syncSendRateInitialBytes.get();
        }

        boolean isEmpty() {
            return responses.isEmpty() && manifests.isEmpty();
        }
    }

    private static long minRate() {
        return Math.max(HARD_MIN_BYTES_PER_SECOND, ModConfig.SERVER.syncSendRateMinBytes.get());
    }

    private static long maxRate() {
        return Math.min(HARD_MAX_BYTES_PER_SECOND, ModConfig.SERVER.syncSendRateMaxBytes.get());
    }

    private static long rateForPing(long pingMs) {
        long min = minRate();
        long max = maxRate();
        if (pingMs <= PING_TARGET_MAX_MS) {
            return max;
        }
        if (pingMs >= PING_TARGET_MIN_MS) {
            return min;
        }
        double t = (double) (pingMs - PING_TARGET_MAX_MS) / (PING_TARGET_MIN_MS - PING_TARGET_MAX_MS);
        return Math.round(max - t * (max - min));
    }

    private static PlayerQueue getQueue(ServerPlayer player) {
        return queues.computeIfAbsent(player.getUUID(), PlayerQueue::new);
    }

    public static void enqueueManifest(ServerPlayer player, SyncManifestPayload part) {
        getQueue(player).manifests.add(part);
    }

    public static void enqueueRegionResponse(
            ServerPlayer player, List<ChunkMapData> parts, int worldId, String status) {
        PlayerQueue q = getQueue(player);
        q.responses.add(new PendingResponse(parts, worldId, status));
        int bytes = 0;
        for (ChunkMapData p : parts) bytes += p.data.length;
        LOGGER.info(
                "[SYNC-SRV] enqueue response for {}: {} parts, {} bytes, worldId={}, status={} (queuedResponses={})",
                player.getName().getString(),
                parts.size(),
                bytes,
                worldId,
                status,
                q.responses.size());
    }

    public static void onRequestReceived(ServerPlayer player) {
        PlayerQueue q = queues.get(player.getUUID());
        if (q == null) {
            return;
        }
        int queuedParts = 0;
        for (PendingResponse r : q.responses) queuedParts += r.remaining();
        LOGGER.info(
                "[SYNC-SRV] request received from {} (queuedResponses={}, queuedParts={})",
                player.getName().getString(),
                q.responses.size(),
                queuedParts);
        long nowNs = System.nanoTime();
        if (q.lastCompleteSentNs == 0) {
            return;
        }
        long rttMs = Math.max(0, (nowNs - q.lastCompleteSentNs) / 1_000_000L);
        q.lastCompleteSentNs = 0;
        if (rttMs > RTT_HIGH_MS) {
            q.rateBytesPerSecond = Math.max(minRate(), (long) (q.rateBytesPerSecond * RTT_SLOW_FACTOR));
            q.suppressUntilNs = nowNs + STRUGGLE_SUPPRESS_MS * 1_000_000L;
            LOGGER.debug("Player {} gap {}ms, braking sync rate to {}/s", q.playerId, rttMs, q.rateBytesPerSecond);
        }
    }

    public static void onPlayerDisconnect(UUID playerId) {
        queues.remove(playerId);
    }

    public static void onServerStopped() {
        queues.clear();
    }

    public static void tick(MinecraftServer server) {
        if (queues.isEmpty()) {
            return;
        }
        long nowNs = System.nanoTime();
        for (PlayerQueue q : queues.values()) {
            try {
                tickPlayer(server, q, nowNs);
            } catch (Exception e) {
                LOGGER.warn("Failed to pump queued data for player {}", q.playerId, e);
                queues.remove(q.playerId);
            }
        }
    }

    private static void tickPlayer(MinecraftServer server, PlayerQueue q, long nowNs) {
        ServerPlayer player = server.getPlayerList().getPlayer(q.playerId);
        if (player == null) {
            queues.remove(q.playerId);
            return;
        }

        long pingMs = player.latency;
        q.pingTargetBytesPerSecond = rateForPing(pingMs);

        if (q.isEmpty()) {
            q.budget = 0;
            q.suppressUntilNs = 0;
            return;
        }

        if (nowNs >= q.suppressUntilNs) {
            long target = q.pingTargetBytesPerSecond;
            q.rateBytesPerSecond = (long) (q.rateBytesPerSecond + (target - q.rateBytesPerSecond) * PING_ADAPT_FACTOR);
            q.rateBytesPerSecond = Math.max(minRate(), Math.min(maxRate(), q.rateBytesPerSecond));
        }

        double dt = (nowNs - q.lastTickNs) / 1_000_000_000.0;
        q.lastTickNs = nowNs;
        if (dt < 0 || dt > MAX_DT_SECONDS) {
            dt = MAX_DT_SECONDS;
        }
        q.budget = Math.min(q.budget + q.rateBytesPerSecond * dt, MAX_ACCUMULATED_BUDGET_BYTES);

        flushEmptyResponses(player, q, nowNs);

        int maxBatchBytes = getMaxPacketSize();
        List<ChunkMapData> batch = new ArrayList<>();
        int batchBytes = 0;
        int currentWorldId = 0;
        String currentStatus = "ok";

        while (q.budget >= ChunkMapData.MAX_PAYLOAD_BYTES) {
            SyncManifestPayload manifest = q.manifests.poll();
            if (manifest != null) {
                sendManifest(player, q, manifest);
                q.budget -= estimateManifestBytes(manifest);
                continue;
            }

            PendingResponse response = q.responses.peek();
            if (response == null) {
                break;
            }
            currentWorldId = response.worldId;
            currentStatus = response.status;

            ChunkMapData part = response.parts.get(response.cursor);
            if (batchBytes > 0 && batchBytes + part.data.length > maxBatchBytes) {
                sendResponse(player, q, currentWorldId, currentStatus, new ArrayList<>(batch), false);
                batch = new ArrayList<>();
                batchBytes = 0;
            }
            batch.add(part);
            batchBytes += part.data.length;
            response.cursor++;
            q.budget -= part.data.length;

            if (response.remaining() == 0) {
                q.responses.poll();
                sendResponse(player, q, response.worldId, response.status, new ArrayList<>(batch), true);
                q.lastCompleteSentNs = nowNs;
                batch = new ArrayList<>();
                batchBytes = 0;
            }
        }

        if (!batch.isEmpty()) {
            sendResponse(player, q, currentWorldId, currentStatus, batch, false);
        }
    }

    private static void flushEmptyResponses(ServerPlayer player, PlayerQueue q, long nowNs) {
        while (true) {
            PendingResponse head = q.responses.peek();
            if (head == null || head.remaining() != 0) {
                return;
            }
            q.responses.poll();
            sendResponse(player, q, head.worldId, head.status, List.of(), true);
            q.lastCompleteSentNs = nowNs;
        }
    }

    private static void sendManifest(ServerPlayer player, PlayerQueue q, SyncManifestPayload part) {
        LOGGER.info(
                "[SYNC-SRV] send manifest part to {}: {} entries",
                player.getName().getString(),
                part.timestamps().size());
        try {
            ForgeNetworkHandler.get().sendToPlayer(player, part);
        } catch (Exception e) {
            LOGGER.warn("Failed to send manifest part to {}, dropping transfer", q.playerId);
            queues.remove(q.playerId);
        }
    }

    private static void sendResponse(
            ServerPlayer player,
            PlayerQueue q,
            int worldId,
            String status,
            List<ChunkMapData> parts,
            boolean complete) {
        int bytes = 0;
        for (ChunkMapData p : parts) bytes += p.data.length;
        int queuedParts = 0;
        for (PendingResponse r : q.responses) queuedParts += r.remaining();
        LOGGER.info(
                "[SYNC-SRV] send to {}: {} parts, {} bytes, complete={}, queuedResponses={}, queuedParts={}",
                player.getName().getString(),
                parts.size(),
                bytes,
                complete,
                q.responses.size(),
                queuedParts);
        try {
            ForgeNetworkHandler.get().sendToPlayer(player, new SyncResponsePayload(parts, complete, worldId, status));
        } catch (Exception e) {
            LOGGER.warn("Failed to send sync response to {}, dropping transfer", q.playerId);
            queues.remove(q.playerId);
        }
    }

    private static long estimateManifestBytes(SyncManifestPayload part) {
        return 32L + part.timestamps().size() * 80L;
    }

    private static int getMaxPacketSize() {
        int configValue = ModConfig.SERVER.maxSyncPacketSize.get();
        return Math.min(configValue, 1_000_000);
    }
}
