package com.mapsyncer;

import java.nio.file.Path;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.payload.SyncManifestPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import com.mapsyncer.server.CommandHandler;
import com.mapsyncer.server.ConversionOrchestrator;
import com.mapsyncer.server.IncrementalUpdateHandlerLogic;
import com.mapsyncer.server.ServerSyncHandlerLogic;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

@Mod(MapSyncer.MOD_ID)
public class MapSyncer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    public static final Path CACHE_DIR = Path.of(MOD_ID);

    private static final String PROTOCOL_VERSION = "7";
    private static SimpleChannel CHANNEL;

    public MapSyncer() {
        ModContainer modContainer = ModLoadingContext.get().getActiveContainer();
        VERSION = modContainer.getModInfo().getVersion().toString();

        ModLoadingContext.get().registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);

        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(MOD_ID, "main"),
                () -> PROTOCOL_VERSION,
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals),
                NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals));
        CHANNEL.registerMessage(
                0,
                SyncRequestPayload.class,
                (msg, buf) -> SyncRequestPayload.write(buf, msg),
                SyncRequestPayload::read,
                ServerSyncHandlerLogic::handleSyncRequest);
        CHANNEL.registerMessage(
                1,
                SyncResponsePayload.class,
                (msg, buf) -> SyncResponsePayload.write(buf, msg),
                SyncResponsePayload::read,
                MapPacketHandler::handleSyncResponse);
        CHANNEL.registerMessage(
                2,
                SyncManifestPayload.class,
                (msg, buf) -> SyncManifestPayload.write(buf, msg),
                SyncManifestPayload::read,
                MapPacketHandler::handleSyncManifest);
        LOGGER.info("MapSyncer initialized");
    }

    public static void sendToServer(SyncRequestPayload payload) {
        CHANNEL.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, SyncResponsePayload payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    public static void sendToPlayer(ServerPlayer player, SyncManifestPayload payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    public static void enqueueWork(Supplier<NetworkEvent.Context> ctx, Runnable work) {
        ctx.get().enqueueWork(work);
    }

    public static ServerPlayer getPlayerFromContext(Supplier<NetworkEvent.Context> ctx) {
        return ctx.get().getSender();
    }

    @EventBusSubscriber(bus = EventBusSubscriber.Bus.MOD)
    public static class ModEvents {
        @SubscribeEvent
        public static void onConfigLoading(ModConfigEvent.Loading event) {
            ModConfig.bindServerConfig(event.getConfig());
        }
    }

    @EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.FORGE)
    public static class ClientEventHandler {
        @SubscribeEvent
        public static void onPlayerLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
            MapPacketHandler.prepareJoinSync();
        }

        @SubscribeEvent
        public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            MapPacketHandler.onDisconnect();
        }
    }

    @EventBusSubscriber(bus = EventBusSubscriber.Bus.FORGE)
    public static class ForgeEvents {
        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            ConversionOrchestrator.cleanupCacheDir();
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            IncrementalUpdateHandlerLogic.get().stop();
        }

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            CommandHandler.register(event.getDispatcher(), "mapsyncer");
        }
    }
}
