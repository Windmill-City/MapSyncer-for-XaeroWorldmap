package com.mapsyncer.network.payload;

import net.minecraft.network.FriendlyByteBuf;

public record RegionRef(String dimId, int caveLayer, int regionX, int regionZ) {

    public int regionDistance(int playerRegionX, int playerRegionZ) {
        return Math.max(Math.abs(regionX - playerRegionX), Math.abs(regionZ - playerRegionZ));
    }

    public static void write(FriendlyByteBuf buf, RegionRef region) {
        buf.writeUtf(region.dimId);
        buf.writeInt(region.caveLayer);
        buf.writeInt(region.regionX);
        buf.writeInt(region.regionZ);
    }

    public static RegionRef read(FriendlyByteBuf buf) {
        String dimId = buf.readUtf();
        int caveLayer = buf.readInt();
        int regionX = buf.readInt();
        int regionZ = buf.readInt();
        return new RegionRef(dimId, caveLayer, regionX, regionZ);
    }
}
