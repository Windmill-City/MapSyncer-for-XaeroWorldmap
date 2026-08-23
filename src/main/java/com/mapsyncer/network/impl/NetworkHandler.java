package com.mapsyncer.network.impl;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.SyncManifestPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class NetworkHandler {

    private static final String PROTOCOL_VERSION = "5";
    private static @Nullable SimpleChannel CHANNEL;

    private static @Nullable BiConsumer<SyncResponsePayload, Supplier<NetworkEvent.Context>> syncResponseHandler;
    private static @Nullable BiConsumer<SyncManifestPayload, Supplier<NetworkEvent.Context>> syncManifestHandler;
    private static @Nullable BiConsumer<SyncRequestPayload, Supplier<NetworkEvent.Context>> syncRequestHandler;

    public static void init() {
        if (CHANNEL != null) return;

        SimpleChannel channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(MapSyncer.MOD_ID, "main"),
                () -> PROTOCOL_VERSION,
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals),
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals));
        CHANNEL = channel;

        channel.registerMessage(
                0,
                ForgeSyncRequestMessage.class,
                ForgeSyncRequestMessage::encode,
                ForgeSyncRequestMessage::decode,
                NetworkHandler::handleSyncRequest);

        channel.registerMessage(
                1,
                ForgeSyncResponseMessage.class,
                ForgeSyncResponseMessage::encode,
                ForgeSyncResponseMessage::decode,
                NetworkHandler::handleSyncResponse);

        channel.registerMessage(
                2,
                ForgeSyncManifestMessage.class,
                ForgeSyncManifestMessage::encode,
                ForgeSyncManifestMessage::decode,
                NetworkHandler::handleSyncManifest);
    }

    private static void handleSyncRequest(ForgeSyncRequestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        if (syncRequestHandler != null) {
            syncRequestHandler.accept(msg.getData(), ctx);
        }
    }

    private static void handleSyncResponse(ForgeSyncResponseMessage msg, Supplier<NetworkEvent.Context> ctx) {
        if (syncResponseHandler != null) {
            syncResponseHandler.accept(msg.getData(), ctx);
        }
    }

    private static void handleSyncManifest(ForgeSyncManifestMessage msg, Supplier<NetworkEvent.Context> ctx) {
        if (syncManifestHandler != null) {
            syncManifestHandler.accept(msg.getData(), ctx);
        }
    }

    public static void sendToServer(SyncRequestPayload payload) {
        channel().sendToServer(new ForgeSyncRequestMessage(payload));
    }

    public static void sendToPlayer(ServerPlayer player, SyncResponsePayload payload) {
        channel().send(PacketDistributor.PLAYER.with(() -> player), new ForgeSyncResponseMessage(payload));
    }

    public static void sendToPlayer(ServerPlayer player, SyncManifestPayload payload) {
        channel().send(PacketDistributor.PLAYER.with(() -> player), new ForgeSyncManifestMessage(payload));
    }

    private static SimpleChannel channel() {
        if (CHANNEL == null) {
            throw new IllegalStateException("ForgeNetworkHandler channel not initialized");
        }
        return CHANNEL;
    }

    public static void registerSyncResponseHandler(
            BiConsumer<SyncResponsePayload, Supplier<NetworkEvent.Context>> handler) {
        syncResponseHandler = handler;
    }

    public static void registerSyncManifestHandler(
            BiConsumer<SyncManifestPayload, Supplier<NetworkEvent.Context>> handler) {
        syncManifestHandler = handler;
    }

    public static void registerSyncRequestHandler(
            BiConsumer<SyncRequestPayload, Supplier<NetworkEvent.Context>> handler) {
        syncRequestHandler = handler;
    }

    public static void enqueueWork(Supplier<NetworkEvent.Context> ctx, Runnable work) {
        ctx.get().enqueueWork(work);
    }

    public static ServerPlayer getPlayerFromContext(Supplier<NetworkEvent.Context> ctx) {
        return ctx.get().getSender();
    }

    public static class ForgeSyncRequestMessage {
        private final SyncRequestPayload data;

        public ForgeSyncRequestMessage(SyncRequestPayload data) {
            this.data = data;
        }

        public SyncRequestPayload getData() {
            return data;
        }

        public static void encode(ForgeSyncRequestMessage msg, FriendlyByteBuf buf) {
            SyncRequestPayload.write(buf, msg.data);
        }

        public static ForgeSyncRequestMessage decode(FriendlyByteBuf buf) {
            return new ForgeSyncRequestMessage(SyncRequestPayload.read(buf));
        }
    }

    public static class ForgeSyncResponseMessage {
        private final SyncResponsePayload data;

        public ForgeSyncResponseMessage(SyncResponsePayload data) {
            this.data = data;
        }

        public SyncResponsePayload getData() {
            return data;
        }

        public static void encode(ForgeSyncResponseMessage msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.data.chunks().size());
            for (ChunkMapData chunk : msg.data.chunks()) {
                encodeChunkMapData(buf, chunk);
            }
            buf.writeBoolean(msg.data.isComplete());
            buf.writeUtf(msg.data.status());
        }

        public static ForgeSyncResponseMessage decode(FriendlyByteBuf buf) {
            int size = buf.readInt();
            List<ChunkMapData> chunks = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                chunks.add(decodeChunkMapData(buf));
            }
            boolean isComplete = buf.readBoolean();
            String status = buf.readUtf();
            return new ForgeSyncResponseMessage(new SyncResponsePayload(chunks, isComplete, status));
        }
    }

    public static class ForgeSyncManifestMessage {
        private final SyncManifestPayload data;

        public ForgeSyncManifestMessage(SyncManifestPayload data) {
            this.data = data;
        }

        public SyncManifestPayload getData() {
            return data;
        }

        public static void encode(ForgeSyncManifestMessage msg, FriendlyByteBuf buf) {
            SyncManifestPayload.write(buf, msg.data);
        }

        public static ForgeSyncManifestMessage decode(FriendlyByteBuf buf) {
            return new ForgeSyncManifestMessage(SyncManifestPayload.read(buf));
        }
    }

    private static void encodeChunkMapData(FriendlyByteBuf buf, ChunkMapData data) {
        buf.writeInt(data.regionX);
        buf.writeInt(data.regionZ);
        buf.writeUtf(data.dimension);
        buf.writeByteArray(data.data);
        buf.writeLong(data.timestampMillis);

        boolean hasCaveLayer = data.caveLayer != Integer.MAX_VALUE;
        buf.writeBoolean(hasCaveLayer);
        if (hasCaveLayer) {
            buf.writeInt(data.caveLayer);
        }
        buf.writeBoolean(data.totalParts > 1);
        if (data.totalParts > 1) {
            buf.writeInt(data.partIndex);
            buf.writeInt(data.totalParts);
        }
    }

    private static ChunkMapData decodeChunkMapData(FriendlyByteBuf buf) {
        int regionX = buf.readInt();
        int regionZ = buf.readInt();
        String dimension = buf.readUtf();
        byte[] data = buf.readByteArray();
        long timestampMillis = buf.readLong();

        int caveLayer = Integer.MAX_VALUE;
        if (buf.isReadable()) {
            boolean hasCaveLayer = buf.readBoolean();
            if (hasCaveLayer) {
                caveLayer = buf.readInt();
            }
        }

        int partIndex = 0;
        int totalParts = 0;
        if (buf.isReadable()) {
            boolean isSplit = buf.readBoolean();
            if (isSplit) {
                partIndex = buf.readInt();
                totalParts = buf.readInt();
            }
        }

        return new ChunkMapData(regionX, regionZ, dimension, data, timestampMillis, caveLayer, partIndex, totalParts);
    }
}
