package com.mapsyncer.network;

import net.minecraft.network.FriendlyByteBuf;

public record MapResponsePayload(RegionData chunk) {

    public static void write(FriendlyByteBuf buf, MapResponsePayload payload) {
        buf.writeBoolean(payload.chunk() != null);
        if (payload.chunk() != null) {
            RegionData.write(buf, payload.chunk());
        }
    }

    public static MapResponsePayload read(FriendlyByteBuf buf) {
        boolean present = buf.readBoolean();
        RegionData chunk = present ? RegionData.read(buf) : null;
        return new MapResponsePayload(chunk);
    }
}
