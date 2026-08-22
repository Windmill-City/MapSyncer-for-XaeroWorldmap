package com.mapsyncer.network;

import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncManifestPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;

import java.util.function.BiConsumer;

public interface NetworkHandler<PLAYER_TYPE, EVENT_TYPE> {

    void registerHandlers(EVENT_TYPE event);

    void sendToServer(SyncRequestPayload payload);

    void sendToPlayer(PLAYER_TYPE player, SyncResponsePayload payload);

    void sendToPlayer(PLAYER_TYPE player, SyncManifestPayload payload);

    void sendToPlayer(PLAYER_TYPE player, ServerInstalledPayload payload);

    void registerSyncResponseHandler(BiConsumer<SyncResponsePayload, PayloadContext> handler);

    void registerSyncManifestHandler(BiConsumer<SyncManifestPayload, PayloadContext> handler);

    void registerServerInstalledHandler(BiConsumer<ServerInstalledPayload, PayloadContext> handler);

    void registerSyncRequestHandler(BiConsumer<SyncRequestPayload, PayloadContext> handler);

    void enqueueWork(PayloadContext context, Runnable work);

    PLAYER_TYPE getPlayerFromContext(PayloadContext context);
}