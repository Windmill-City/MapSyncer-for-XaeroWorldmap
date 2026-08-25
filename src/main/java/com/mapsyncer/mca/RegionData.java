package com.mapsyncer.mca;

import net.minecraft.network.FriendlyByteBuf;

public class RegionData {

    public final RegionRef ref;

    public final byte[] data;

    public RegionData(RegionRef ref, byte[] data) {
        this.ref = ref;
        this.data = data;
    }

    public static void write(FriendlyByteBuf buf, RegionData data) {
        RegionRef.write(buf, data.ref);
        buf.writeByteArray(data.data);
    }

    public static RegionData read(FriendlyByteBuf buf) {
        RegionRef ref = RegionRef.read(buf);
        byte[] data = buf.readByteArray();
        return new RegionData(ref, data);
    }
}
