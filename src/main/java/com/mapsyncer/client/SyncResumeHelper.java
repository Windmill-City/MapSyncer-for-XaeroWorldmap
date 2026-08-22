package com.mapsyncer.client;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.util.ChatUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

@EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.FORGE)
public class SyncResumeHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncResumeHelper.class);

    @SubscribeEvent
    public static void onPlayerLoggingInEvent(ClientPlayerNetworkEvent.LoggingIn event) {
        onPlayerLoggingIn();
    }

    public static void onPlayerLoggingIn() {
        LOGGER.info("Player logging in to server, checking sync state...");

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            LOGGER.warn("Player is null during LoggingIn event");
            return;
        }

        if (ModConfig.CLIENT.isAutoSyncEnabled()) {
            MapPacketHandler.prepareJoinSync();
        }

        Thread resumeCheckThread = new Thread(() -> {
            mc.execute(() -> checkInterruptedSync(mc));
        }, "mapsyncer-resume-check");
        resumeCheckThread.setDaemon(true);
        resumeCheckThread.start();
    }

    private static void checkInterruptedSync(Minecraft mc) {
        if (ModConfig.CLIENT.isAutoSyncEnabled()) {
            LOGGER.debug("Join auto-sync enabled, skip resume prompt");
            return;
        }

        Path serverDir = XaeroMapIntegrator.getClientXaeroWorldMapDir();
        if (serverDir == null || !serverDir.toFile().exists()) {
            LOGGER.info("Server directory not found, skip sync state check");
            return;
        }

        ClientTimestampCache.resetInstance();
        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
        if (tsCache == null || !tsCache.cacheFileExists()) {
            return;
        }

        if (!tsCache.needsResume()) {
            LOGGER.debug("No resume needed: state={}", tsCache.getSyncState());
            return;
        }

        String syncCommand = tsCache.getSyncCommand();
        if (mc.player != null && !syncCommand.isEmpty()) {
            showResumePrompt(mc, syncCommand);
        }
    }

    private static void showResumePrompt(Minecraft mc, String command) {
        Component message = ChatUtils.prefix()
                .append(ChatUtils.desc("mapsyncer.sync.interrupted"))
                .append(Component.literal(" "))
                .append(Component.literal(command));
        ChatUtils.sendChatMessage(message);
    }

}