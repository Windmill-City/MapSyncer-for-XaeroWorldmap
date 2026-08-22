package com.mapsyncer.server;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mapsyncer.config.DimensionConfigParser;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.UpdateMode;
import com.mapsyncer.server.ConversionOrchestrator.DimensionCacheStats;
import com.mapsyncer.server.ConversionOrchestrator.SingleRegionResult;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.ApiHelper;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.ModLogConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

public class CacheCommandHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheCommandHandler.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, String prefix) {
        dispatcher.register(Commands.literal(prefix)
                .requires(ApiHelper.admin())
                .executes(ctx -> showHelp(ctx, prefix))
                .then(Commands.literal("generate")
                        .executes(CacheCommandHandler::generateAll)
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .executes(CacheCommandHandler::generateDimension)
                                .then(Commands.literal("--force")
                                        .executes(CacheCommandHandler::generateDimensionForce))
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(CacheCommandHandler::generateSingleRegion)))))
                .then(Commands.literal("stop")
                        .executes(CacheCommandHandler::stopConversion))
                .then(Commands.literal("status")
                        .executes(CacheCommandHandler::showStatus))
                .then(Commands.literal("incremental")
                        .executes(CacheCommandHandler::showIncrementalMode)
                        .then(Commands.literal("off")
                                .executes(CacheCommandHandler::setIncrementalOff))
                        .then(Commands.literal("onempty")
                                .executes(CacheCommandHandler::setIncrementalOnEmpty)))
                .then(Commands.literal("reloadconfig")
                        .executes(CacheCommandHandler::reloadConfig))
                .then(Commands.literal("help")
                        .executes(ctx -> showHelp(ctx, prefix))));
    }

    private static int showHelp(CommandContext<CommandSourceStack> ctx, String prefix) {
        showHelp(component -> ctx.getSource().sendSuccess(() -> component, false), prefix);
        return Command.SINGLE_SUCCESS;
    }

    private static int showIncrementalMode(CommandContext<CommandSourceStack> ctx) {
        showIncrementalMode(component -> ctx.getSource().sendSuccess(() -> component, false));
        return Command.SINGLE_SUCCESS;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        if (reloadConfig(ctx.getSource().getServer())) {
            ctx.getSource().sendSuccess(
                    () -> ChatUtils.success("mapsyncer.command.config_reloaded"), false);
        } else {
            ctx.getSource().sendFailure(
                    ChatUtils.error("mapsyncer.command.config_reload_failed"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int generateAll(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        if (!generateAll(server, () -> {
            String dimList = String.join(", ", getCompletedDimensions());
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.full_complete",
                    getProcessedCount(),
                    getTotalCount(),
                    getCompletedDimensions().size(),
                    dimList), false);
        })) {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.conversion_busy"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_full"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generateDimension(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();
        String dimensionId = getDimensionId(dimension);
        String friendlyName = getFriendlyDimensionName(dimension);

        if (!generateDimension(ctx.getSource().getServer(), dimensionId, () -> {
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.dim_complete",
                    getProcessedCount(),
                    getTotalCount(),
                    getUpdatedCount()), false);
        })) {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.conversion_busy"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_dim", friendlyName), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generateDimensionForce(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();
        String dimensionId = getDimensionId(dimension);
        String friendlyName = getFriendlyDimensionName(dimension);

        if (!generateDimensionForce(ctx.getSource().getServer(), dimensionId, () -> {
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.force_complete",
                    getProcessedCount(),
                    getTotalCount(),
                    getUpdatedCount()), false);
        })) {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.conversion_busy"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_force", friendlyName), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generateSingleRegion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        ResourceKey<Level> dimension = level.dimension();
        MinecraftServer server = ctx.getSource().getServer();

        if (!checkRegionExists(server, dimension, x, z)) {
            String friendlyName = getFriendlyDimensionName(dimension);
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.region_not_found", x, z, friendlyName));
            return 0;
        }

        String friendlyName = getFriendlyDimensionName(dimension);

        if (!generateSingleRegion(server, dimension, x, z, result -> {
            if (result == SingleRegionResult.SUCCESS) {
                ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.region_converted"), false);
            } else if (result == SingleRegionResult.CONVERSION_FAILED) {
                ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.region_conversion_failed", x, z));
            }
        })) {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.conversion_busy"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.command.generating_region", x, z, friendlyName), false);

        return Command.SINGLE_SUCCESS;
    }

    private static int stopConversion(CommandContext<CommandSourceStack> ctx) {
        if (ConversionOrchestrator.requestCancel()) {
            ctx.getSource().sendSuccess(
                    () -> ChatUtils.success("mapsyncer.command.conversion_stopped"), false);
        } else {
            ctx.getSource().sendFailure(
                    ChatUtils.error("mapsyncer.command.conversion_not_running"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(CacheCommandHandler::generationStatusMessage, false);
        ctx.getSource().sendSuccess(CacheCommandHandler::incrementalStatusMessage, false);

        List<DimensionCacheStats> cacheStats = getCacheStats();
        if (!cacheStats.isEmpty()) {
            int totalDims = cacheStats.size();
            int totalRegions = cacheStats.stream().mapToInt(DimensionCacheStats::regionCount).sum();
            long totalSize = cacheStats.stream().mapToLong(DimensionCacheStats::sizeBytes).sum();

            StringBuilder dims = new StringBuilder();
            for (DimensionCacheStats stat : cacheStats) {
                if (dims.length() > 0) dims.append("\n");
                dims.append(String.format("  %s: %d regions, %.2f MB",
                        stat.dimension(), stat.regionCount(), stat.sizeMB()));
            }

            ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.status.cache_detail",
                    totalDims, totalRegions, String.format("%.2f", totalSize / (1024.0 * 1024.0)),
                    dims.toString()), false);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalOff(CommandContext<CommandSourceStack> ctx) {
        disableIncremental();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_disabled"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setIncrementalOnEmpty(CommandContext<CommandSourceStack> ctx) {
        setIncrementalOnEmpty(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_on_empty_set"), false);
        return Command.SINGLE_SUCCESS;
    }

    public static String serverCommandPrefix() {
        return "mapsyncer";
    }

    public static void showHelp(Consumer<net.minecraft.network.chat.Component> sender) {
        showHelp(sender, serverCommandPrefix());
    }

    public static void showHelp(Consumer<net.minecraft.network.chat.Component> sender, String prefix) {
        sender.accept(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.help.server.header")));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_dim", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_region", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_force", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.stop", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.status", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental_off", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental_onempty", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.reloadconfig", prefix));
    }

    public static void showIncrementalMode(Consumer<net.minecraft.network.chat.Component> sender) {
        sender.accept(incrementalStatusMessage());
        sender.accept(ChatUtils.desc(
                "mapsyncer.command.incremental_status_hint", serverCommandPrefix()));
    }

    public static MutableComponent generationStatusMessage() {
        if (ConversionOrchestrator.isRunning()) {
            return ChatUtils.message("mapsyncer.generate.in_progress",
                    ConversionOrchestrator.getProcessedCount(),
                    ConversionOrchestrator.getTotalCount(),
                    ConversionOrchestrator.getStatus());
        }
        return ChatUtils.message("mapsyncer.generate.no_progress");
    }

    public static MutableComponent incrementalStatusMessage() {
        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();

        if (mode == UpdateMode.ON_EMPTY) {
            return ChatUtils.message("mapsyncer.command.incremental_status_on_empty");
        }
        return ChatUtils.message("mapsyncer.command.incremental_status_disabled");
    }

    public static boolean generateAll(MinecraftServer server, Runnable onSuccess) {
        if (ConversionOrchestrator.isRunning()) {
            LOGGER.warn("Conversion already in progress, rejecting generateAll command");
            return false;
        }

        server.saveEverything(false, true, true);
        Thread worker = new Thread(() -> {
            if (ConversionOrchestrator.generateAll(server) && onSuccess != null) {
                onSuccess.run();
            }
        }, "xaero-map-generator");
        worker.start();
        return true;
    }

    public static boolean generateDimension(MinecraftServer server, String dimensionId, Runnable onSuccess) {
        if (ConversionOrchestrator.isRunning()) {
            LOGGER.warn("Conversion already in progress, rejecting generateDimension command");
            return false;
        }
        server.saveEverything(false, true, true);
        Thread worker = new Thread(() -> {
            if (ConversionOrchestrator.generateDimension(server, dimensionId) && onSuccess != null) {
                onSuccess.run();
            }
        }, "xaero-map-generator");
        worker.start();
        return true;
    }

    public static boolean generateDimensionForce(MinecraftServer server, String dimensionId, Runnable onSuccess) {
        if (ConversionOrchestrator.isRunning()) {
            LOGGER.warn("Conversion already in progress, rejecting generateDimensionForce command");
            return false;
        }
        server.saveEverything(false, true, true);
        Thread worker = new Thread(() -> {
            if (ConversionOrchestrator.generateDimensionForce(server, dimensionId) && onSuccess != null) {
                onSuccess.run();
            }
        }, "xaero-map-generator");
        worker.start();
        return true;
    }

    public static boolean checkRegionExists(MinecraftServer server, ResourceKey<Level> dimension, int x, int z) {
        return ConversionOrchestrator.checkMcaFileExists(server, dimension, x, z) != null;
    }

    public static boolean generateSingleRegion(MinecraftServer server, ResourceKey<Level> dimension, int x, int z,
                                            Consumer<SingleRegionResult> resultHandler) {
        if (ConversionOrchestrator.isRunning()) {
            LOGGER.warn("Conversion already in progress, rejecting generateSingleRegion command");
            resultHandler.accept(SingleRegionResult.ALREADY_RUNNING);
            return false;
        }

        server.saveEverything(false, true, true);
        Thread worker = new Thread(() -> {
            SingleRegionResult result = ConversionOrchestrator.generateSingleRegion(server, dimension, x, z);
            if (resultHandler != null) {
                resultHandler.accept(result);
            }
        }, "xaero-map-generator");
        worker.start();
        return true;
    }

    public static List<DimensionCacheStats> getCacheStats() {
        return ConversionOrchestrator.getCacheStats();
    }

    public static List<String> getCompletedDimensions() {
        return ConversionOrchestrator.getCompletedDimensions();
    }

    public static int getProcessedCount() {
        return ConversionOrchestrator.getProcessedCount();
    }

    public static int getTotalCount() {
        return ConversionOrchestrator.getTotalCount();
    }

    public static int getUpdatedCount() {
        return ConversionOrchestrator.getUpdatedCount();
    }

    public static void disableIncremental() {
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.DISABLED);
        ModConfig.SERVER_SPEC.save();
        IncrementalUpdateHandlerLogic.getInstance().stop();
    }

    public static void setIncrementalOnEmpty(MinecraftServer server) {
        ModConfig.SERVER.incrementalUpdateMode.set(UpdateMode.ON_EMPTY);
        ModConfig.SERVER_SPEC.save();
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    public static UpdateMode getIncrementalUpdateMode() {
        return ModConfig.SERVER.incrementalUpdateMode.get();
    }

    public static boolean reloadConfig(MinecraftServer server) {
        try {
            ModConfig.reloadServerFromDisk();
            ModLogConfig.applyDebugLogging();
            DimensionRegistry.resetRegistration();
            DimensionConfigParser.invalidateCache();

            if (!ConversionOrchestrator.isRunning()) {
                ConversionOrchestrator.shutdownExecutor();
            }

            IncrementalUpdateHandlerLogic handler = IncrementalUpdateHandlerLogic.getInstance();
            handler.stop();
            UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode.get();
            if (mode != UpdateMode.DISABLED && server != null) {
                handler.start(server);
            }

            LOGGER.info("Server configuration reloaded");
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to reload server configuration", e);
            return false;
        }
    }

    public static String getFriendlyDimensionName(ResourceKey<Level> dimension) {
        return DimensionPathMapping.getInstance().getFriendlyName(ApiHelper.getDimId(dimension));
    }

    public static String getDimensionId(ResourceKey<Level> dimension) {
        return ApiHelper.getDimId(dimension);
    }
}
