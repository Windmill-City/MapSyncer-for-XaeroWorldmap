package com.mapsyncer.network.payload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;

public class SyncManifestPayload {
    private final Map<String, Long> timestamps;

    private final int partIndex;

    private final int totalParts;

    public SyncManifestPayload(Map<String, Long> timestamps) {
        this(timestamps, 0, 0);
    }

    public SyncManifestPayload(Map<String, Long> timestamps, int partIndex, int totalParts) {
        this.timestamps = timestamps != null ? timestamps : Map.of();
        this.partIndex = partIndex;
        this.totalParts = totalParts;
    }

    public Map<String, Long> timestamps() {
        return timestamps;
    }

    public int partIndex() {
        return partIndex;
    }

    public int totalParts() {
        return totalParts;
    }

    private static final int MAX_ENTRIES_PER_PART = 256;

    public static SyncManifestPayload[] split(Map<String, Long> timestamps) {
        int maxEntriesPerPart = Math.max(1, MAX_ENTRIES_PER_PART);
        if (timestamps.size() <= maxEntriesPerPart) {
            return new SyncManifestPayload[] {new SyncManifestPayload(timestamps)};
        }

        List<Map.Entry<String, Long>> entries = new ArrayList<>(timestamps.entrySet());
        int totalParts = (entries.size() + maxEntriesPerPart - 1) / maxEntriesPerPart;
        SyncManifestPayload[] parts = new SyncManifestPayload[totalParts];

        for (int i = 0; i < totalParts; i++) {
            int start = i * maxEntriesPerPart;
            int end = Math.min(start + maxEntriesPerPart, entries.size());
            Map<String, Long> partMap = new LinkedHashMap<>();
            for (int j = start; j < end; j++) {
                partMap.put(entries.get(j).getKey(), entries.get(j).getValue());
            }
            parts[i] = new SyncManifestPayload(partMap, i, totalParts);
        }
        return parts;
    }

    public static void write(FriendlyByteBuf buf, SyncManifestPayload payload) {
        buf.writeInt(payload.timestamps().size());
        for (Map.Entry<String, Long> entry : payload.timestamps().entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeLong(entry.getValue());
        }
        buf.writeBoolean(payload.totalParts() > 1);
        if (payload.totalParts() > 1) {
            buf.writeInt(payload.partIndex());
            buf.writeInt(payload.totalParts());
        }
    }

    public static SyncManifestPayload read(FriendlyByteBuf buf) {
        int size = buf.readInt();
        Map<String, Long> timestamps = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String path = buf.readUtf();
            long timestampMillis = buf.readLong();
            timestamps.put(path, timestampMillis);
        }

        int partIndex = 0;
        int totalParts = 0;
        boolean isSplit = buf.readBoolean();
        if (isSplit) {
            partIndex = buf.readInt();
            totalParts = buf.readInt();
        }

        return new SyncManifestPayload(timestamps, partIndex, totalParts);
    }
}
