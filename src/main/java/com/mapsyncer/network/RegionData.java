package com.mapsyncer.network;

import net.minecraft.network.FriendlyByteBuf;

public class RegionData {

    public final RegionRef ref;

    public final long timestampMillis;

    public final byte[] data;

    public RegionData(RegionRef ref, long timestampMillis, byte[] data) {
        this.ref = ref;
        this.timestampMillis = timestampMillis;
        this.data = data;
    }

    public static void write(FriendlyByteBuf buf, RegionData data) {
        RegionRef.write(buf, data.ref);
        buf.writeLong(data.timestampMillis);
        buf.writeByteArray(data.data);
    }

    public static RegionData read(FriendlyByteBuf buf) {
        RegionRef ref = RegionRef.read(buf);
        long timestampMillis = buf.readLong();
        byte[] data = buf.readByteArray();
        return new RegionData(ref, timestampMillis, data);
    }
}
