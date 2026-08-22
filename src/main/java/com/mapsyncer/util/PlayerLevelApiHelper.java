package com.mapsyncer.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class PlayerLevelApiHelper {

    private PlayerLevelApiHelper() {}

    public static MinecraftServer getServer(ServerPlayer player) {
        return player.serverLevel().getServer();
    }
}
