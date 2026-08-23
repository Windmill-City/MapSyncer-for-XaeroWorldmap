package com.mapsyncer.network;

import net.minecraft.network.FriendlyByteBuf;

public class RegionData {

    public static final int MAX_PAYLOAD_BYTES = 28_000;

    public final RegionRef ref;

    public final long timestampMillis;

    public final byte[] data;

    public final int partIndex;

    public final int totalParts;

    public RegionData(RegionRef ref, long timestampMillis, byte[] data) {
        this(ref, timestampMillis, data, 0, 0);
    }

    public RegionData(RegionRef ref, long timestampMillis, byte[] data, int partIndex, int totalParts) {
        this.ref = ref;
        this.timestampMillis = timestampMillis;
        this.data = data;
        this.partIndex = partIndex;
        this.totalParts = totalParts;
    }

    public boolean isSurfaceLayer() {
        return ref.caveLayer() == Integer.MAX_VALUE;
    }

    public static RegionData[] split(RegionData original) {
        int totalParts = (original.data.length + MAX_PAYLOAD_BYTES - 1) / MAX_PAYLOAD_BYTES;
        if (totalParts <= 1) {
            return new RegionData[] {original};
        }
        RegionData[] parts = new RegionData[totalParts];
        for (int i = 0; i < totalParts; i++) {
            int offset = i * MAX_PAYLOAD_BYTES;
            int len = Math.min(MAX_PAYLOAD_BYTES, original.data.length - offset);
            byte[] partData = new byte[len];
            System.arraycopy(original.data, offset, partData, 0, len);
            parts[i] = new RegionData(original.ref, original.timestampMillis, partData, i, totalParts);
        }
        return parts;
    }

    public static void write(FriendlyByteBuf buf, RegionData data) {
        RegionRef.write(buf, data.ref);
        buf.writeLong(data.timestampMillis);
        buf.writeByteArray(data.data);
        buf.writeBoolean(data.totalParts > 1);
        if (data.totalParts > 1) {
            buf.writeInt(data.partIndex);
            buf.writeInt(data.totalParts);
        }
    }

    public static RegionData read(FriendlyByteBuf buf) {
        RegionRef ref = RegionRef.read(buf);
        long timestampMillis = buf.readLong();
        byte[] data = buf.readByteArray();

        int partIndex = 0;
        int totalParts = 0;
        if (buf.isReadable()) {
            boolean isSplit = buf.readBoolean();
            if (isSplit) {
                partIndex = buf.readInt();
                totalParts = buf.readInt();
            }
        }

        return new RegionData(ref, timestampMillis, data, partIndex, totalParts);
    }
}
