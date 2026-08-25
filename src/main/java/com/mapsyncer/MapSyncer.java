package com.mapsyncer;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mapsyncer.client.XaeroBridge;
import com.mapsyncer.mca.Plan;
import com.mapsyncer.mca.XaeroWriter;
import com.mapsyncer.network.ManifestPayload;
import com.mapsyncer.network.RequestPayload;
import com.mapsyncer.network.ResponsePayload;
import com.mapsyncer.server.AutoUpdater;
import com.mapsyncer.server.CommandHandler;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(MapSyncer.MOD_ID)
public class MapSyncer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LogManager.getLogger(MapSyncer.class);

    private static final ForgeConfigSpec CONFIG_SPEC;
    private static final ServerConfig CONFIG;

    private static ModConfig serverConfig;

    private static final String PROTOCOL_VERSION = "4";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals));

    static {
        var pair = new ForgeConfigSpec.Builder().configure(ServerConfig::new);
        CONFIG = pair.getLeft();
        CONFIG_SPEC = pair.getRight();
        Plan.build(CONFIG.Plans.get());
    }

    private static class ServerConfig {

        private final ConfigValue<Boolean> AutoUpdate;

        private final ConfigValue<List<? extends String>> Plans;

        public ServerConfig(ForgeConfigSpec.Builder builder) {
            builder.push("AutoUpdate");

            AutoUpdate =
                    builder.comment("Update map when no players are online").define("enabled", true);

            builder.pop();

            builder.push("LayerPlans");
            builder.comment("Dimension scan settings");

            Plans = builder.comment(
                            "Per-dimension scan configuration list (one line per dimension)",
                            "Format per entry: \"dimension = layerPlan\"",
                            "layerPlan: SURFACE, explicit Y (e.g. 64), or combos (e.g. SURFACE,64)",
                            "Example: \"minecraft:overworld = SURFACE\"")
                    .defineList("plans", Plan.getDefaults(), obj -> obj instanceof String);

            builder.pop();
        }
    }

    public static boolean isAutoUpdate() {
        return CONFIG.AutoUpdate.get();
    }

    public static void setAutoUpdate(boolean enabled) {
        CONFIG.AutoUpdate.set(enabled);
        CONFIG_SPEC.save();
    }

    public static void bindServerConfig(ModConfig config) {
        serverConfig = config;
        Plan.build(CONFIG.Plans.get());
    }

    public static void reloadFromDisk() {
        if (serverConfig != null) {
            Path path = serverConfig.getFullPath();
            CommentedFileConfig file = CommentedFileConfig.of(path);
            try {
                file.load();
                CONFIG_SPEC.acceptConfig(file);
                Plan.build(CONFIG.Plans.get());
            } finally {
                file.close();
            }
        }
    }

    public MapSyncer() {
        ModContainer modContainer = ModList.get()
                .getModContainerById(MOD_ID)
                .orElseThrow(() -> new IllegalStateException("Mod container not found for " + MOD_ID));
        VERSION = modContainer.getModInfo().getVersion().toString();

        modContainer.addConfig(new ModConfig(Type.SERVER, CONFIG_SPEC, modContainer));

        CHANNEL.registerMessage(
                0,
                RequestPayload.class,
                (msg, buf) -> RequestPayload.write(buf, msg),
                RequestPayload::read,
                com.mapsyncer.server.PacketHandler::handleRequest);
        CHANNEL.registerMessage(
                1,
                ResponsePayload.class,
                (msg, buf) -> ResponsePayload.write(buf, msg),
                ResponsePayload::read,
                com.mapsyncer.client.PacketHandler::handleResponse);
        CHANNEL.registerMessage(
                2,
                ManifestPayload.class,
                (msg, buf) -> ManifestPayload.write(buf, msg),
                ManifestPayload::read,
                com.mapsyncer.client.PacketHandler::handleManifest);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            LOGGER.info("Initializing Xaero WorldMap bridge...");
            if (XaeroBridge.initialize()) {
                LOGGER.info("Xaero WorldMap bridge initialized successfully");
            } else {
                LOGGER.error("Xaero WorldMap bridge initialization failed, Xaero WorldMap unavailable");
            }
        }
        LOGGER.info("MapSyncer initialized");
    }

    public static void sendToServer(RequestPayload payload) {
        CHANNEL.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, ResponsePayload payload) {
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
            bindServerConfig(event.getConfig());
        }
    }

    @EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.FORGE)
    public static class ClientEventHandler {
        @SubscribeEvent
        public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            com.mapsyncer.client.PacketHandler.onDisconnect();
        }
    }

    @EventBusSubscriber(bus = EventBusSubscriber.Bus.FORGE)
    public static class ForgeEvents {
        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            XaeroWriter.cleanStaleFiles();
            AutoUpdater.performScan(event.getServer());
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            AutoUpdater.stop();
        }

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            CommandHandler.register(event.getDispatcher());
        }
    }

    @EventBusSubscriber(value = Dist.DEDICATED_SERVER, bus = EventBusSubscriber.Bus.FORGE)
    public static class ServerEvents {
        @SubscribeEvent
        public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
            ServerPlayer player = (ServerPlayer) event.getEntity();
            com.mapsyncer.server.PacketHandler.pushManifest(player);
            AutoUpdater.stop();
        }

        @SubscribeEvent
        public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            ServerPlayer player = (ServerPlayer) event.getEntity();
            AutoUpdater.onPlayerLoggedOut(player);
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            AutoUpdater.stop();
        }
    }
}
