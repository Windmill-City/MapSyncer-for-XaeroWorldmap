package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.UpdateMode;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.impl.ForgeNetworkHandler;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(value = {Dist.CLIENT, Dist.DEDICATED_SERVER}, bus = EventBusSubscriber.Bus.FORGE)
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
        ForgeNetworkHandler.onPlayerDisconnect(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        onServerStopped();
    }

    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (server == null) return;

        long lastGenTime = GenerationCache.getInstance(ConversionOrchestrator.getCacheDir()).getLastGenerationTime();
        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
        NetworkManager.sendToPlayer(player,
            new ServerInstalledPayload(getModVersion(), lastGenTime, mode));

        if (!ConversionOrchestrator.isRunning() && mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandlerLogic.getInstance().start(server);
        }
    }

    public static void onServerStopped() {
        ServerLifecycleBridge.onServerStopped();
    }

    private static String getModVersion() {
        try {
            return com.mapsyncer.MapSyncer.VERSION;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
