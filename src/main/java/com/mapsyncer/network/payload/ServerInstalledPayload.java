package com.mapsyncer.network.payload;

import com.mapsyncer.config.UpdateMode;
import com.mapsyncer.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;

public record ServerInstalledPayload(
        String version,
        long lastGenerationTimestamp,
        int autoSyncIntervalMinutes,
        UpdateMode updateMode) {

    public static final String ID = NetworkHandler.SERVER_INSTALLED_ID;

    public ServerInstalledPayload(String version, long lastGenerationTimestamp, int autoSyncIntervalMinutes) {
        this(version, lastGenerationTimestamp, autoSyncIntervalMinutes, UpdateMode.DISABLED);
    }

    public static void write(FriendlyByteBuf buf, ServerInstalledPayload payload) {
        buf.writeUtf(payload.version());
        buf.writeLong(payload.lastGenerationTimestamp());
        buf.writeInt(payload.autoSyncIntervalMinutes());
        buf.writeByte(payload.updateMode().ordinal());
    }

    public static ServerInstalledPayload read(FriendlyByteBuf buf) {
        String version = buf.readUtf();
        long lastGen = buf.readLong();
        int intervalMinutes = buf.readInt();
        UpdateMode mode = readUpdateMode(buf.readByte());
        return new ServerInstalledPayload(version, lastGen, intervalMinutes, mode);
    }

    private static UpdateMode readUpdateMode(int ordinal) {
        UpdateMode[] values = UpdateMode.values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return UpdateMode.DISABLED;
    }
}
