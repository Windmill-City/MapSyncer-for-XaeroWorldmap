package com.mapsyncer.network;

import com.mapsyncer.mca.RegionRef;
import net.minecraft.network.FriendlyByteBuf;

public class RequestPayload {
    private final RegionRef region;

    public RequestPayload(RegionRef region) {
        this.region = region;
    }

    public RegionRef region() {
        return region;
    }

    public static void write(FriendlyByteBuf buf, RequestPayload payload) {
        RegionRef.write(buf, payload.region());
    }

    public static RequestPayload read(FriendlyByteBuf buf) {
        return new RequestPayload(RegionRef.read(buf));
    }
}
