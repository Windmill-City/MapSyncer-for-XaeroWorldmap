package com.mapsyncer;

import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.impl.NetworkHandler;
import com.mapsyncer.server.CommandHandler;
import com.mapsyncer.server.ConversionOrchestrator;
import com.mapsyncer.server.IncrementalUpdateHandlerLogic;
import com.mapsyncer.server.ServerSyncHandlerLogic;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MapSyncer.MOD_ID)
public class MapSyncer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    public MapSyncer() {
        ModContainer modContainer = ModLoadingContext.get().getActiveContainer();
        VERSION = modContainer.getModInfo().getVersion().toString();

        ModLoadingContext.get().registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);

        NetworkHandler.init();
        LOGGER.info("NetworkHandler initialized");

        if (FMLEnvironment.dist == Dist.CLIENT) {
            MapPacketHandler.init();
            LOGGER.info("MapSyncer initialized");
        }

        ServerSyncHandlerLogic.init();
        LOGGER.info("MapSyncer server handlers registered");
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
