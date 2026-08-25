package com.mapsyncer.network;

import net.minecraft.network.FriendlyByteBuf;

public record ResponsePayload(RegionData chunk) {

    public static void write(FriendlyByteBuf buf, ResponsePayload payload) {
        buf.writeBoolean(payload.chunk() != null);
        if (payload.chunk() != null) {
            RegionData.write(buf, payload.chunk());
        }
    }

    public static ResponsePayload read(FriendlyByteBuf buf) {
        boolean present = buf.readBoolean();
        RegionData chunk = present ? RegionData.read(buf) : null;
        return new ResponsePayload(chunk);
    }
}
