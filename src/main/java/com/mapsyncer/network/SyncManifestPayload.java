package com.mapsyncer.network;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;

public class SyncManifestPayload {
    private final Map<RegionRef, Long> timestamps;

    public SyncManifestPayload(Map<RegionRef, Long> timestamps) {
        this.timestamps = timestamps != null ? timestamps : Map.of();
    }

    public Map<RegionRef, Long> timestamps() {
        return timestamps;
    }

    public static void write(FriendlyByteBuf buf, SyncManifestPayload payload) {
        buf.writeInt(payload.timestamps().size());
        for (Map.Entry<RegionRef, Long> entry : payload.timestamps().entrySet()) {
            RegionRef.write(buf, entry.getKey());
            buf.writeLong(entry.getValue());
        }
    }

    public static SyncManifestPayload read(FriendlyByteBuf buf) {
        int size = buf.readInt();
        Map<RegionRef, Long> timestamps = new HashMap<>();
        for (int i = 0; i < size; i++) {
            RegionRef ref = RegionRef.read(buf);
            long timestampMillis = buf.readLong();
            timestamps.put(ref, timestampMillis);
        }
        return new SyncManifestPayload(timestamps);
    }
}
