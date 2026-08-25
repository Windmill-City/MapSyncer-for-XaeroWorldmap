package com.mapsyncer.mca;

import net.minecraft.network.FriendlyByteBuf;

public class RegionData {

    private final RegionRef ref;

    private final byte[] data;

    public RegionData(RegionRef ref, byte[] data) {
        this.ref = ref;
        this.data = data;
    }

    public RegionRef ref() {
        return ref;
    }

    public byte[] data() {
        return data;
    }

    public static void write(FriendlyByteBuf buf, RegionData data) {
        RegionRef.write(buf, data.ref());
        buf.writeByteArray(data.data());
    }

    public static RegionData read(FriendlyByteBuf buf) {
        RegionRef ref = RegionRef.read(buf);
        byte[] data = buf.readByteArray();
        return new RegionData(ref, data);
    }
}
