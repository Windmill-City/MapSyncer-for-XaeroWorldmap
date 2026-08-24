package com.mapsyncer.network;

import net.minecraft.network.FriendlyByteBuf;

public class MapRequestPayload {
    private final RegionRef region;

    public MapRequestPayload(RegionRef region) {
        this.region = region;
    }

    public RegionRef region() {
        return region;
    }

    public static void write(FriendlyByteBuf buf, MapRequestPayload payload) {
        RegionRef.write(buf, payload.region());
    }

    public static MapRequestPayload read(FriendlyByteBuf buf) {
        return new MapRequestPayload(RegionRef.read(buf));
    }
}
