package com.mapsyncer.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(value = Dist.DEDICATED_SERVER, bus = EventBusSubscriber.Bus.FORGE)
public class PlayerJoinHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerJoinHandlerLogic.class);

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer player = (ServerPlayer) event.getEntity();

        if (MapConverter.isRunning()) {
            MapConverter.requestCancel();
        }

        ServerSyncHandlerLogic.pushManifestOnJoin(player);
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        MinecraftServer server = player.getServer();
        if (server == null) return;

        MapUpdater.get().onPlayerLoggedOut(server);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LOGGER.info("Server stopped, cleaning up singleton cache instances");

        MapUpdater.get().stop();

        LOGGER.info("Singleton cache cleanup completed");
    }
}
