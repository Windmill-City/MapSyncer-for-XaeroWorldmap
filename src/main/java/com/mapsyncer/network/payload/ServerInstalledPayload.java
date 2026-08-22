package com.mapsyncer.network.payload;

import com.mapsyncer.config.UpdateMode;
import net.minecraft.network.FriendlyByteBuf;

public record ServerInstalledPayload(
        String version,
        long lastGenerationTimestamp,
        UpdateMode updateMode) {

    public static void write(FriendlyByteBuf buf, ServerInstalledPayload payload) {
        buf.writeUtf(payload.version());
        buf.writeLong(payload.lastGenerationTimestamp());
        buf.writeByte(payload.updateMode().ordinal());
    }

    public static ServerInstalledPayload read(FriendlyByteBuf buf) {
        String version = buf.readUtf();
        long lastGen = buf.readLong();
        UpdateMode mode = readUpdateMode(buf.readByte());
        return new ServerInstalledPayload(version, lastGen, mode);
    }

    private static UpdateMode readUpdateMode(int ordinal) {
        UpdateMode[] values = UpdateMode.values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return UpdateMode.DISABLED;
    }
}
