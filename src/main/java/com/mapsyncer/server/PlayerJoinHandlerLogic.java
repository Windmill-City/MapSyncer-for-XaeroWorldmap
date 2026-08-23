package com.mapsyncer.server;

import com.mapsyncer.client.ManifestClient;
import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.client.XaeroMapDataHandler;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.UpdateMode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(
        value = {Dist.CLIENT, Dist.DEDICATED_SERVER},
        bus = EventBusSubscriber.Bus.FORGE)
public class PlayerJoinHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerJoinHandlerLogic.class);

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        MinecraftServer server = player.getServer();
        onPlayerJoin(player, server);
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        SyncTransferScheduler.onPlayerDisconnect(event.getEntity().getUUID());
        IncrementalUpdateHandlerLogic.getInstance().onPlayerLoggedOut();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        cleanupSingletons();
    }

    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (server == null) return;

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();

        if (ConversionOrchestrator.isRunning()) {
            ConversionOrchestrator.requestCancel();
        }

        if (!ConversionOrchestrator.isRunning() && mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandlerLogic.getInstance().start(server);
        }

        ServerSyncHandlerLogic.pushManifestOnJoin(player);
    }

    private static void cleanupSingletons() {
        LOGGER.info("Server stopped, cleaning up singleton cache instances");

        ConversionOrchestrator.shutdownExecutor();

        IncrementalUpdateHandlerLogic.resetInstance();

        SyncTransferScheduler.onServerStopped();

        MapPacketHandler.clearReceivedChunks();
        XaeroMapDataHandler.clearRegionTracking();
        BlockPropertyResolver.clearCache();
        ManifestClient.shutdown();

        LOGGER.info("Singleton cache cleanup completed");
    }
}
