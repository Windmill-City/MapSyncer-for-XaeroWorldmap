package com.mapsyncer.server;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.server.MapConverter.DimensionCacheStats;
import com.mapsyncer.util.ChatUtils;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandHandler.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mapsyncer")
                .requires(source -> source.hasPermission(4))
                .executes(ctx -> showHelp(ctx))
                .then(Commands.literal("generate")
                        .executes(ctx -> showHelp(ctx))
                        .then(Commands.literal("start").executes(CommandHandler::generateStart))
                        .then(Commands.literal("stop").executes(CommandHandler::generateStop))
                        .then(Commands.literal("status").executes(CommandHandler::generateStatus)))
                .then(Commands.literal("autoupdate")
                        .executes(CommandHandler::showAutoUpdateStatus)
                        .then(Commands.literal("off").executes(CommandHandler::setAutoUpdateOff))
                        .then(Commands.literal("on").executes(CommandHandler::setAutoUpdateOn)))
                .then(Commands.literal("reloadconfig").executes(CommandHandler::reloadConfig))
                .then(Commands.literal("help").executes(ctx -> showHelp(ctx))));
    }

    private static int showHelp(CommandContext<CommandSourceStack> ctx) {
        Consumer<Component> sender = component -> ctx.getSource().sendSuccess(() -> component, false);
        sender.accept(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.help.server.header")));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_start"));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_stop"));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_status"));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.autoupdate"));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.autoupdate_off"));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.autoupdate_on"));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.reloadconfig"));
        return Command.SINGLE_SUCCESS;
    }

    private static int showAutoUpdateStatus(CommandContext<CommandSourceStack> ctx) {
        Consumer<Component> sender = component -> ctx.getSource().sendSuccess(() -> component, false);
        if (MapSyncer.isAutoUpdate()) {
            sender.accept(ChatUtils.message("mapsyncer.command.autoupdate_on"));
        } else {
            sender.accept(ChatUtils.message("mapsyncer.command.autoupdate_off"));
        }
        sender.accept(ChatUtils.desc("mapsyncer.command.autoupdate_status_hint"));
        return Command.SINGLE_SUCCESS;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        try {
            MapSyncer.reloadFromDisk();
            LOGGER.info("Server configuration reloaded");
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.config_reloaded"), false);
        } catch (Exception e) {
            LOGGER.error("Failed to reload server configuration", e);
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.config_reload_failed"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int generateStart(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        Util.ioPool().execute(() -> {
            if (MapConverter.generate(server)) {
                String dimList = String.join(", ", MapConverter.getCompletedDimensions());
                ctx.getSource()
                        .sendSuccess(
                                () -> ChatUtils.success(
                                        "mapsyncer.generate.full_complete",
                                        MapConverter.getProcessedCount(),
                                        MapConverter.getTotalCount(),
                                        MapConverter.getCompletedDimensions().size(),
                                        dimList),
                                false);
            }
        });
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_full"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generateStop(CommandContext<CommandSourceStack> ctx) {
        if (MapConverter.requestCancel()) {
            ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.conversion_stopped"), false);
        } else {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.conversion_not_running"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int generateStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource()
                .sendSuccess(
                        () -> {
                            if (MapConverter.isRunning()) {
                                return ChatUtils.message(
                                        "mapsyncer.generate.in_progress",
                                        MapConverter.getProcessedCount(),
                                        MapConverter.getTotalCount());
                            }
                            return ChatUtils.message("mapsyncer.generate.no_progress");
                        },
                        false);

        List<DimensionCacheStats> cacheStats = MapConverter.getCacheStats();
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

    private static int setAutoUpdateOff(CommandContext<CommandSourceStack> ctx) {
        MapSyncer.setAutoUpdate(false);
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.autoupdate_off"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int setAutoUpdateOn(CommandContext<CommandSourceStack> ctx) {
        MapSyncer.setAutoUpdate(true);
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.autoupdate_on"), false);
        return Command.SINGLE_SUCCESS;
    }
}
