package com.mapsyncer.server;

import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.impl.ForgeNetworkHandler;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(value = {Dist.CLIENT, Dist.DEDICATED_SERVER}, bus = EventBusSubscriber.Bus.FORGE)
public class PlayerJoinHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerJoinHandlerLogic.class);

    private static final int CLEANUP_CHECK_INTERVAL_TICKS = 1200;

    private static int cleanupTickCounter = 0;

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        MinecraftServer server = player.getServer();
        onPlayerJoin(player, server);
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        onPlayerLeave(event.getEntity().getUUID());
        ForgeNetworkHandler.onPlayerDisconnect(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        onServerStopped();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        onServerTick(event.getServer());
    }

    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (server == null) return;

        long lastGenTime = GenerationCache.getInstance(ConversionOrchestrator.getCacheDir()).getLastGenerationTime();
        UpdateMode mode = PlatformManager.getPlatform().getIncrementalUpdateMode();
        int intervalTicks = PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks();
        int autoInterval = AutoSyncConfig.computeInterval(mode, intervalTicks);
        NetworkManager.sendToPlayer(player,
            new ServerInstalledPayload(getModVersion(), lastGenTime, autoInterval, mode, intervalTicks));

        if (!ConversionOrchestrator.isRunning() && mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandlerLogic.getInstance().start(server);
        }
    }

    public static void onPlayerLeave(UUID playerId) {
        ServerSyncHandlerLogic.onPlayerDisconnect(playerId);
    }

    public static void onServerStopped() {
        ServerLifecycleBridge.onServerStopped();
    }

    public static void onServerTick(MinecraftServer server) {
        cleanupTickCounter++;

        if (cleanupTickCounter < CLEANUP_CHECK_INTERVAL_TICKS) {
            return;
        }
        cleanupTickCounter = 0;

        if (server == null) return;

        Set<UUID> onlinePlayerIds = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            onlinePlayerIds.add(player.getUUID());
        }

        ServerSyncHandlerLogic.cleanupOfflinePlayers(onlinePlayerIds);
    }

    private static String getModVersion() {
        try {
            return com.mapsyncer.MapSyncer.VERSION;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
