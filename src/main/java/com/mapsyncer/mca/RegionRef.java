package com.mapsyncer.mca;

import net.minecraft.network.FriendlyByteBuf;

public record RegionRef(String dimId, int cave, int X, int Z) {

    static final int SURFACE_CAVE = Integer.MAX_VALUE;

    public int regionDistance(int playerRegionX, int playerRegionZ) {
        return Math.max(Math.abs(X - playerRegionX), Math.abs(Z - playerRegionZ));
    }

    public boolean isSurface() {
        return cave == SURFACE_CAVE;
    }

    public static void write(FriendlyByteBuf buf, RegionRef region) {
        buf.writeUtf(region.dimId);
        buf.writeInt(region.cave);
        buf.writeInt(region.X);
        buf.writeInt(region.Z);
    }

    public static RegionRef read(FriendlyByteBuf buf) {
        String dimId = buf.readUtf();
        int cave = buf.readInt();
        int regionX = buf.readInt();
        int regionZ = buf.readInt();
        return new RegionRef(dimId, cave, regionX, regionZ);
    }
}
