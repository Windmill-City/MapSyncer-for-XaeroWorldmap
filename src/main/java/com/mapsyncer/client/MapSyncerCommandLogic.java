package com.mapsyncer.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.util.ClientMeta;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.server.CacheCommandHandler;
import com.mapsyncer.util.ChatUtils;

import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.FORGE)
public class MapSyncerCommandLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapSyncerCommandLogic.class);

    @SubscribeEvent
    public static void registerClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                net.minecraft.commands.Commands.literal("mapsyncer")
                        .executes(ctx -> { showHelp(ctx.getSource().hasPermission(4)); return Command.SINGLE_SUCCESS; })
                        .then(net.minecraft.commands.Commands.literal("help")
                                .executes(ctx -> { showHelp(ctx.getSource().hasPermission(4)); return Command.SINGLE_SUCCESS; }))
                        .then(net.minecraft.commands.Commands.literal("sync")
                                .executes(ctx -> executeSyncCurrentDim())
                                .then(net.minecraft.commands.Commands.literal("all")
                                        .executes(ctx -> executeSyncAll(false)))
                                .then(net.minecraft.commands.Commands.argument("dimension", DimensionArgument.dimension())
                                        .suggests((ctx, builder) -> { suggestDimensions(builder); return builder.buildFuture(); })
                                        .executes(ctx -> {
                                            ResourceLocation loc = ctx.getArgument("dimension", ResourceLocation.class);
                                            return executeSyncDimension(loc.toString());
                                        })))

                        .then(net.minecraft.commands.Commands.literal("autosync")
                                .executes(ctx -> executeAutoSyncStatus())
                                .then(net.minecraft.commands.Commands.literal("on")
                                        .executes(ctx -> setClientAutoSync(true)))
                                .then(net.minecraft.commands.Commands.literal("off")
                                        .executes(ctx -> setClientAutoSync(false))))
        );
    }

    public static void showHelp(boolean hasServerPermission) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        mc.player.displayClientMessage(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.command.help_header")), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_sync"), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_sync_dim"), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_sync_all"), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_autosync"), false);
        mc.player.displayClientMessage(ChatUtils.header("mapsyncer.command.help_dimension_note"), false);

        if (hasServerPermission) {
            CacheCommandHandler.showHelp(ChatUtils::sendChatMessage);
        }
    }

    public static void suggestDimensions(SuggestionsBuilder builder) {
        builder.suggest("minecraft:overworld");
        builder.suggest("minecraft:the_nether");
        builder.suggest("minecraft:the_end");
        builder.suggest("all");

        Set<String> added = new HashSet<>();

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != null) {
            ResourceKey<Level> currentDim = level.dimension();
            ResourceLocation currentLoc = currentDim.location();
            if (!"minecraft".equals(currentLoc.getNamespace())) {
                String suggestion = currentLoc.toString();
                builder.suggest(suggestion);
                added.add(suggestion);
            }

            level.registryAccess().registry(Registries.DIMENSION_TYPE).ifPresent(registry -> {
                for (var key : registry.registryKeySet()) {
                    ResourceLocation loc = key.location();
                    String namespace = loc.getNamespace();
                    if ("minecraft".equals(namespace)) continue;

                    String path = loc.getPath();
                    String dimPath = path.endsWith("_type") ? path.substring(0, path.length() - 5) : path;
                    String suggestion = namespace + ":" + dimPath;
                    if (!added.contains(suggestion)) {
                        builder.suggest(suggestion);
                        added.add(suggestion);
                    }
                }
            });

            level.registryAccess().registry(Registries.LEVEL_STEM).ifPresent(registry -> {
                for (var key : registry.registryKeySet()) {
                    ResourceLocation loc = key.location();
                    String namespace = loc.getNamespace();
                    if ("minecraft".equals(namespace)) continue;
                    String suggestion = loc.toString();
                    if (!added.contains(suggestion)) {
                        builder.suggest(suggestion);
                        added.add(suggestion);
                    }
                }
            });
        }

        Path baseDir = XaeroMapIntegrator.getCurrentServerBaseDirectory();
        if (baseDir != null) {
            try (Stream<Path> dirs = Files.list(baseDir)) {
                dirs.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("mw$"))
                    .forEach(p -> {
                        String dirName = p.getFileName().toString();
                        String suggestion = xaeroDirToDimensionId(dirName);
                        if (suggestion != null && !suggestion.isEmpty() && !added.contains(suggestion)) {
                            builder.suggest(suggestion);
                            added.add(suggestion);
                        }
                    });
            } catch (IOException e) {
                LOGGER.warn("Failed to scan Xaero directory", e);
            }
        }
    }

    public static String xaeroDirToDimensionId(String dirName) {
        if ("null".equals(dirName)) return "overworld";
        if ("DIM-1".equals(dirName)) return "the_nether";
        if ("DIM1".equals(dirName)) return "the_end";
        if (dirName.contains("$")) return dirName.replace('$', ':');
        if (dirName.startsWith("DIM")) return "";
        return dirName;
    }

    public static int executeSyncDimension(String dimInput) {
        if ("all".equalsIgnoreCase(dimInput)) {
            return executeSyncAll(false);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        String dimensionId = resolveDimensionId(dimInput, mc.level);
        sendSyncRequest(mc, dimensionId, false, false);

        return 1;
    }

    public static int executeSyncAll(boolean silent) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;

        sendSyncRequest(mc, "all", true, silent);
        return 1;
    }

    public static int executeSyncCurrentDim() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        ResourceKey<Level> currentDim = mc.level.dimension();
        String dimensionId = currentDim.location().toString();
        sendSyncRequest(mc, dimensionId, false, false);

        return 1;
    }

    public static String resolveDimensionId(String input, ClientLevel level) {
        switch (input.toLowerCase()) {
            case "overworld": return "minecraft:overworld";
            case "nether": case "the_nether": return "minecraft:the_nether";
            case "end": case "the_end": return "minecraft:the_end";
        }

        if (input.contains(":")) return input;

        var optRegistry = level.registryAccess().registry(Registries.DIMENSION_TYPE);
        if (optRegistry.isPresent()) {
            var registry = optRegistry.get();
            for (var key : registry.registryKeySet()) {
                ResourceLocation loc = key.location();
                if ("minecraft".equals(loc.getNamespace())) continue;
                String path = loc.getPath();
                String dimPath = path.endsWith("_type") ? path.substring(0, path.length() - 5) : path;
                if (dimPath.equals(input) || path.equals(input)) {
                    return loc.getNamespace() + ":" + dimPath;
                }
            }
        }

        return "minecraft:" + input;
    }

    public static void sendSyncRequest(Minecraft mc, String dimensionId, boolean syncAll, boolean silent) {
        if (MapPacketHandler.isSyncInProgress() || ClientHashManager.isComputingMeta()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(ChatUtils.error("mapsyncer.sync.in_progress"), false);
            }
            return;
        }

        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();

        ClientTimestampCache tsCache = serverDir != null && serverDir.toFile().exists()
                ? ClientTimestampCache.getInstance(serverDir) : null;

        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        String xaeroDim = syncAll ? null : dimMapping.toXaeroDimension(dimensionId);

        Path scanDir = null;
        Map<String, ClientMeta> immediateMeta = null;

        if (syncAll) {
            if (serverDir != null && tsCache != null && tsCache.cacheFileExists()) {
                scanDir = serverDir;
            } else {
                immediateMeta = new java.util.HashMap<>();
                LOGGER.debug("First sync all, sending empty request");
            }
        } else if (tsCache != null && tsCache.cacheFileExists() && tsCache.hasDimensionSynced(xaeroDim)) {
            Path dimDir = serverDir.resolve(xaeroDim);
            Path mwDir = findMwDir(dimDir);
            if (mwDir != null) {
                scanDir = mwDir;
            } else {
                immediateMeta = new java.util.HashMap<>();
                immediateMeta.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
                LOGGER.warn("Dimension {} has cache but no mw$ dir", dimensionId);
            }
        } else {
            immediateMeta = new java.util.HashMap<>();
            immediateMeta.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
            LOGGER.debug("First sync for {}", dimensionId);
        }

        if (immediateMeta != null) {
            dispatchSyncRequest(mc, dimensionId, syncAll, serverDir, tsCache, xaeroDim, immediateMeta, silent);
            return;
        }

        ClientHashManager.computeMetaForSyncAsync(scanDir, result ->
                mc.execute(() -> {
                    if (mc.player == null) {
                        return;
                    }
                    if (!result.isSuccess()) {
                        if (result.failedFiles() > 0) {
                            mc.player.displayClientMessage(
                                    ChatUtils.error("mapsyncer.sync.hash_scan_partial", result.failedFiles()), false);
                        } else {
                            mc.player.displayClientMessage(
                                    ChatUtils.error("mapsyncer.sync.hash_scan_failed"), false);
                        }
                        return;
                    }
                    LOGGER.debug("Sync hash scan complete: {} entries", result.meta().size());
                    dispatchSyncRequest(mc, dimensionId, syncAll, serverDir, tsCache, xaeroDim, result.meta(), silent);
                }));
    }

    private static void dispatchSyncRequest(Minecraft mc, String dimensionId, boolean syncAll,
            Path serverDir, ClientTimestampCache tsCache, String xaeroDim,
            Map<String, ClientMeta> metaMap, boolean silent) {
        if (MapPacketHandler.isSyncInProgress()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(ChatUtils.error("mapsyncer.sync.in_progress"), false);
            }
            return;
        }

        LOGGER.debug("Sending sync request with {} entries (serverDir={})", metaMap.size(), serverDir);

        if (tsCache != null) {
            Set<String> dimensions = new HashSet<>();
            if (syncAll) {
                dimensions.add("all");
            } else {
                dimensions.add(xaeroDim);
            }
            String command = syncAll ? "/mapsyncer sync all" : "/mapsyncer sync " + dimensionId;
            tsCache.markSyncStart(dimensions, command);
        }

        SyncRequestPayload[] parts = SyncRequestPayload.split(metaMap, syncAll,
                syncAll ? "" : (xaeroDim != null ? xaeroDim : ""), silent);
        for (SyncRequestPayload part : parts) {
            NetworkManager.sendToServer(part);
        }
        SyncProgressTracker.startTracking();
    }

    public static Path findMwDir(Path dimDir) {
        if (dimDir == null || !dimDir.toFile().exists()) return null;
        try (var dirs = Files.list(dimDir)) {
            return dirs.filter(p -> p.getFileName().toString().startsWith("mw$"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public static int executeAutoSyncStatus() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        boolean enabled = PlatformManager.getPlatform().isClientAutoSyncEnabled();
        mc.player.displayClientMessage(
                ChatUtils.prefix().append(ChatUtils.desc(
                        enabled ? "mapsyncer.autosync.client.enabled" : "mapsyncer.autosync.client.disabled")),
                false);
        return 1;
    }

    public static int setClientAutoSync(boolean enabled) {
        PlatformManager.getPlatform().setClientAutoSyncEnabled(enabled);
        if (!enabled) {
            AutoSyncManager.stopPeriodicSync();
        }
        return executeAutoSyncStatus();
    }
}
