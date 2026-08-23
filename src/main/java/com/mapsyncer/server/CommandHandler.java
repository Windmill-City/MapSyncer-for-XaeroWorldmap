package com.mapsyncer.server;

import com.mapsyncer.config.DimensionConfigParser;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.UpdateMode;
import com.mapsyncer.server.ConversionOrchestrator.DimensionCacheStats;
import com.mapsyncer.util.ApiHelper;
import com.mapsyncer.util.ChatUtils;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandHandler.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, String prefix) {
        dispatcher.register(Commands.literal(prefix)
                .requires(ApiHelper.admin())
                .executes(ctx -> showHelp(ctx, prefix))
                .then(Commands.literal("generate")
                        .executes(ctx -> showHelp(ctx, prefix))
                        .then(Commands.literal("start").executes(CommandHandler::generateAll))
                        .then(Commands.literal("stop").executes(CommandHandler::stopConversion))
                        .then(Commands.literal("status").executes(CommandHandler::showStatus)))
                .then(Commands.literal("incremental")
                        .executes(CommandHandler::showIncrementalMode)
                        .then(Commands.literal("off").executes(CommandHandler::setIncrementalOff))
                        .then(Commands.literal("onempty").executes(CommandHandler::setIncrementalOnEmpty)))
                .then(Commands.literal("reloadconfig").executes(CommandHandler::reloadConfig))
                .then(Commands.literal("help").executes(ctx -> showHelp(ctx, prefix))));
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
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.config_reloaded"), false);
        } else {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.config_reload_failed"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int generateAll(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        if (!generateAll(server, () -> {
            String dimList = String.join(", ", getCompletedDimensions());
            ctx.getSource()
                    .sendSuccess(
                            () -> ChatUtils.success(
                                    "mapsyncer.generate.full_complete",
                                    getProcessedCount(),
                                    getTotalCount(),
                                    getCompletedDimensions().size(),
                                    dimList),
                            false);
        })) {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.conversion_busy"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_full"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int stopConversion(CommandContext<CommandSourceStack> ctx) {
        if (ConversionOrchestrator.requestCancel()) {
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.conversion_stopped"), false);
        } else {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.conversion_not_running"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(CommandHandler::generationStatusMessage, false);
        ctx.getSource().sendSuccess(CommandHandler::incrementalStatusMessage, false);

        List<DimensionCacheStats> cacheStats = getCacheStats();
        if (!cacheStats.isEmpty()) {
            int totalDims = cacheStats.size();
            int totalRegions = cacheStats.stream()
                    .mapToInt(DimensionCacheStats::regionCount)
                    .sum();
            long totalSize = cacheStats.stream()
                    .mapToLong(DimensionCacheStats::sizeBytes)
                    .sum();

            StringBuilder dims = new StringBuilder();
            for (DimensionCacheStats stat : cacheStats) {
                if (dims.length() > 0) dims.append("\n");
                dims.append(String.format(
                        "  %s: %d regions, %.2f MB", stat.dimension(), stat.regionCount(), stat.sizeMB()));
            }

            ctx.getSource()
                    .sendSuccess(
                            () -> ChatUtils.message(
                                    "mapsyncer.status.cache_detail",
                                    totalDims,
                                    totalRegions,
                                    String.format("%.2f", totalSize / (1024.0 * 1024.0)),
                                    dims.toString()),
                            false);
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
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_start", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_stop", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_status", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental_off", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental_onempty", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.reloadconfig", prefix));
    }

    public static void showIncrementalMode(Consumer<net.minecraft.network.chat.Component> sender) {
        sender.accept(incrementalStatusMessage());
        sender.accept(ChatUtils.desc("mapsyncer.command.incremental_status_hint", serverCommandPrefix()));
    }

    public static MutableComponent generationStatusMessage() {
        if (ConversionOrchestrator.isRunning()) {
            return ChatUtils.message(
                    "mapsyncer.generate.in_progress",
                    ConversionOrchestrator.getProcessedCount(),
                    ConversionOrchestrator.getTotalCount());
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
        Thread worker = new Thread(
                () -> {
                    if (ConversionOrchestrator.generateAll(server) && onSuccess != null) {
                        onSuccess.run();
                    }
                },
                "xaero-map-generator");
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

    public static boolean reloadConfig(MinecraftServer server) {
        try {
            ModConfig.reloadServerFromDisk();
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
}
