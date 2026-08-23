package com.mapsyncer.network.payload;

import com.mapsyncer.util.RegionMeta;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;

public class SyncRequestPayload {
    private final Map<String, RegionMeta> regionMeta;
    private final int partIndex;
    private final int totalParts;

    public SyncRequestPayload(Map<String, RegionMeta> clientMeta) {
        this(clientMeta, 0, 0);
    }

    public SyncRequestPayload(Map<String, RegionMeta> clientMeta, int partIndex, int totalParts) {
        this.regionMeta = clientMeta;
        this.partIndex = partIndex;
        this.totalParts = totalParts;
    }

    public Map<String, RegionMeta> regionMeta() {
        return regionMeta;
    }

    public int partIndex() {
        return partIndex;
    }

    public int totalParts() {
        return totalParts;
    }

    public static void write(FriendlyByteBuf buf, SyncRequestPayload payload) {
        buf.writeInt(payload.regionMeta().size());
        for (var entry : payload.regionMeta().entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeLong(entry.getValue().timestampMillis());
        }
        buf.writeBoolean(payload.totalParts() > 1);
        if (payload.totalParts() > 1) {
            buf.writeInt(payload.partIndex());
            buf.writeInt(payload.totalParts());
        }
    }

    public static SyncRequestPayload read(FriendlyByteBuf buf) {
        int size = buf.readInt();
        Map<String, RegionMeta> metaMap = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String path = buf.readUtf();
            long timestampMillis = buf.readLong();
            metaMap.put(path, new RegionMeta(timestampMillis));
        }

        int partIndex = 0;
        int totalParts = 0;
        boolean isSplit = buf.readBoolean();
        if (isSplit) {
            partIndex = buf.readInt();
            totalParts = buf.readInt();
        }

        return new SyncRequestPayload(metaMap, partIndex, totalParts);
    }
}
