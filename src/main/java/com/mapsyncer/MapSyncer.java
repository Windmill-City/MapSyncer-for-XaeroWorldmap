package com.mapsyncer;

import com.mapsyncer.client.XaeroWorldMapBridge;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.ManifestPayload;
import com.mapsyncer.network.MapRequestPayload;
import com.mapsyncer.network.MapResponsePayload;
import com.mapsyncer.server.CommandHandler;
import com.mapsyncer.server.MapConverter;
import com.mapsyncer.server.IdleUpdater;
import java.nio.file.Path;
import java.util.function.Supplier;
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
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MapSyncer.MOD_ID)
public class MapSyncer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    public static final Path CACHE_DIR = Path.of(MOD_ID);

    private static final String PROTOCOL_VERSION = "4";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals));

    public MapSyncer() {
        ModContainer modContainer = ModLoadingContext.get().getActiveContainer();
        VERSION = modContainer.getModInfo().getVersion().toString();

        ModLoadingContext.get().registerConfig(Type.SERVER, ModConfig.SERVER.spec());

        CHANNEL.registerMessage(
                0,
                MapRequestPayload.class,
                (msg, buf) -> MapRequestPayload.write(buf, msg),
                MapRequestPayload::read,
                com.mapsyncer.server.MapPacketHandler::handleMapRequest);
        CHANNEL.registerMessage(
                1,
                MapResponsePayload.class,
                (msg, buf) -> MapResponsePayload.write(buf, msg),
                MapResponsePayload::read,
                com.mapsyncer.client.MapPacketHandler::handleSyncResponse);
        CHANNEL.registerMessage(
                2,
                ManifestPayload.class,
                (msg, buf) -> ManifestPayload.write(buf, msg),
                ManifestPayload::read,
                com.mapsyncer.client.MapPacketHandler::handleSyncManifest);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            LOGGER.info("Initializing Xaero WorldMap bridge...");
            if (XaeroWorldMapBridge.initialize()) {
                LOGGER.info("XaeroWorldMapBridge initialized successfully");
            } else {
                LOGGER.error("XaeroWorldMapBridge initialization failed, Xaero WorldMap unavailable");
            }
        }
        LOGGER.info("MapSyncer initialized");
    }

    public static void sendToServer(MapRequestPayload payload) {
        CHANNEL.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, MapResponsePayload payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    public static void sendToPlayer(ServerPlayer player, ManifestPayload payload) {
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
        public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            com.mapsyncer.client.MapPacketHandler.onDisconnect();
        }
    }

    @EventBusSubscriber(bus = EventBusSubscriber.Bus.FORGE)
    public static class ForgeEvents {
        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            MapConverter.cleanupCacheDir();
            IdleUpdater.performUpdate(event.getServer());
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            IdleUpdater.stop();
        }

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            CommandHandler.register(event.getDispatcher(), "mapsyncer");
        }
    }
}
