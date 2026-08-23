package com.mapsyncer;

import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.UpdateMode;
import com.mapsyncer.network.impl.ForgeNetworkHandler;
import com.mapsyncer.server.CommandHandler;
import com.mapsyncer.server.ConversionOrchestrator;
import com.mapsyncer.server.DimensionRegistry;
import com.mapsyncer.server.IncrementalUpdateHandlerLogic;
import com.mapsyncer.server.ServerSyncHandlerLogic;
import com.mapsyncer.server.SyncTransferScheduler;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
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

        DimensionPathMapping.getInstance().initialize();
        LOGGER.info("DimensionPathMapping initialized");

        ModLoadingContext.get().registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);

        ForgeNetworkHandler networkHandler = new ForgeNetworkHandler();
        ForgeNetworkHandler.setInstance(networkHandler);
        networkHandler.registerHandlers();
        LOGGER.info("NetworkHandler initialized");

        if (FMLEnvironment.dist == Dist.CLIENT) {
            MapPacketHandler.registerHandlers();
            LOGGER.info("MapSyncer initialized");
        }

        ServerSyncHandlerLogic.registerHandlers();
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
        public static void onServerStarting(ServerStartingEvent event) {

            ConversionOrchestrator.tryInitIntegratedServerCache(event.getServer(), FMLPaths.GAMEDIR.get());

            DimensionRegistry.registerAllDimensions(event.getServer());

            UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
            if (mode != UpdateMode.DISABLED) {
                IncrementalUpdateHandlerLogic.getInstance().start(event.getServer());
            }
        }

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            IncrementalUpdateHandlerLogic.getInstance().triggerStartupUpdate();
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            IncrementalUpdateHandlerLogic.getInstance().stop();
            SyncTransferScheduler.onServerStopped();
        }

        @SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            SyncTransferScheduler.tick(event.getServer());
        }

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            CommandHandler.register(event.getDispatcher(), "mapsyncer");
        }
    }
}
