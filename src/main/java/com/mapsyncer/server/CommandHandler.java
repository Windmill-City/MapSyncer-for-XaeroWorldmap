package com.mapsyncer.server;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.server.MapConverter.DimensionCacheStats;
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
                .then(Commands.literal("automatic")
                        .executes(CommandHandler::showAutomaticMode)
                        .then(Commands.literal("off").executes(CommandHandler::setAutomaticOff))
                        .then(Commands.literal("on").executes(CommandHandler::setAutomaticOn)))
                .then(Commands.literal("reloadconfig").executes(CommandHandler::reloadConfig))
                .then(Commands.literal("help").executes(ctx -> showHelp(ctx, prefix))));
    }

    private static int showHelp(CommandContext<CommandSourceStack> ctx, String prefix) {
        showHelp(component -> ctx.getSource().sendSuccess(() -> component, false), prefix);
        return Command.SINGLE_SUCCESS;
    }

    private static int showAutomaticMode(CommandContext<CommandSourceStack> ctx) {
        showAutomaticMode(component -> ctx.getSource().sendSuccess(() -> component, false));
        return Command.SINGLE_SUCCESS;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        if (reloadConfig()) {
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
        if (MapConverter.requestCancel()) {
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.conversion_stopped"), false);
        } else {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.conversion_not_running"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(CommandHandler::generationStatusMessage, false);
        ctx.getSource().sendSuccess(CommandHandler::automaticStatusMessage, false);

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
                        "  %s: %d regions, %.2f MB", stat.dimId(), stat.regionCount(), stat.sizeMB()));
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

    private static int setAutomaticOff(CommandContext<CommandSourceStack> ctx) {
        disableAutomatic();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.automatic_disabled"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setAutomaticOn(CommandContext<CommandSourceStack> ctx) {
        setAutomaticOn();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.automatic_on_set"), false);
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
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_start", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_stop", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_status", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.automatic", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.automatic_off", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.automatic_on", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.reloadconfig", prefix));
    }

    public static void showAutomaticMode(Consumer<net.minecraft.network.chat.Component> sender) {
        sender.accept(automaticStatusMessage());
        sender.accept(ChatUtils.desc("mapsyncer.command.automatic_status_hint", serverCommandPrefix()));
    }

    public static MutableComponent generationStatusMessage() {
        if (MapConverter.isRunning()) {
            return ChatUtils.message(
                    "mapsyncer.generate.in_progress", MapConverter.getProcessedCount(), MapConverter.getTotalCount());
        }
        return ChatUtils.message("mapsyncer.generate.no_progress");
    }

    public static MutableComponent automaticStatusMessage() {
        boolean enabled = ModConfig.SERVER.config().automaticUpdateEnabled.get();

        if (enabled) {
            return ChatUtils.message("mapsyncer.command.automatic_status_on");
        }
        return ChatUtils.message("mapsyncer.command.automatic_status_disabled");
    }

    public static boolean generateAll(MinecraftServer server, Runnable onSuccess) {
        if (MapConverter.isRunning()) {
            LOGGER.warn("Conversion already in progress, rejecting generateAll command");
            return false;
        }

        Thread worker = new Thread(
                () -> {
                    if (MapConverter.generate(server) && onSuccess != null) {
                        onSuccess.run();
                    }
                },
                "xaero-map-generator");
        worker.start();
        return true;
    }

    public static List<DimensionCacheStats> getCacheStats() {
        return MapConverter.getCacheStats();
    }

    public static List<String> getCompletedDimensions() {
        return MapConverter.getCompletedDimensions();
    }

    public static int getProcessedCount() {
        return MapConverter.getProcessedCount();
    }

    public static int getTotalCount() {
        return MapConverter.getTotalCount();
    }

    public static void disableAutomatic() {
        ModConfig.SERVER.config().automaticUpdateEnabled.set(false);
        ModConfig.SERVER.spec().save();
        AutoUpdater.stop();
    }

    public static void setAutomaticOn() {
        ModConfig.SERVER.config().automaticUpdateEnabled.set(true);
        ModConfig.SERVER.spec().save();
    }

    public static boolean reloadConfig() {
        try {
            ModConfig.reloadServerFromDisk();

            AutoUpdater.stop();

            LOGGER.info("Server configuration reloaded");
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to reload server configuration", e);
            return false;
        }
    }
}
