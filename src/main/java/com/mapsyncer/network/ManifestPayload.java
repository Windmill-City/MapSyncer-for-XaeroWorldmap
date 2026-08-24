package com.mapsyncer.network;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;

public class ManifestPayload {
    private final Map<RegionRef, Long> timestamps;

    public ManifestPayload(Map<RegionRef, Long> timestamps) {
        this.timestamps = Map.copyOf(timestamps);
    }

    public Map<RegionRef, Long> timestamps() {
        return timestamps;
    }

    public static void write(FriendlyByteBuf buf, ManifestPayload payload) {
        buf.writeInt(payload.timestamps().size());
        for (Map.Entry<RegionRef, Long> entry : payload.timestamps().entrySet()) {
            RegionRef.write(buf, entry.getKey());
            buf.writeLong(entry.getValue());
        }
    }

    public static ManifestPayload read(FriendlyByteBuf buf) {
        int size = buf.readInt();
        Map<RegionRef, Long> timestamps = new HashMap<>();
        for (int i = 0; i < size; i++) {
            RegionRef ref = RegionRef.read(buf);
            long timestampMillis = buf.readLong();
            timestamps.put(ref, timestampMillis);
        }
        return new ManifestPayload(timestamps);
    }
}
