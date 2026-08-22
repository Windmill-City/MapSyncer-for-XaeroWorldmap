package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.util.ClientMeta;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyncRequestPayload {
    public static final String ID = NetworkHandler.SYNC_REQUEST_ID;

    public static final int MAX_PAYLOAD_BYTES = 28_000;

    private final Map<String, ClientMeta> clientMeta;
    private final int partIndex;
    private final int totalParts;
    private final boolean syncAll;
    private final String targetDimension;
    private final boolean silent;

    public SyncRequestPayload(Map<String, ClientMeta> clientMeta) {
        this(clientMeta, 0, 0, false, "", false);
    }

    public SyncRequestPayload(Map<String, ClientMeta> clientMeta, int partIndex, int totalParts) {
        this(clientMeta, partIndex, totalParts, false, "", false);
    }

    public SyncRequestPayload(Map<String, ClientMeta> clientMeta, boolean syncAll, String targetDimension) {
        this(clientMeta, 0, 0, syncAll, targetDimension, false);
    }

    public SyncRequestPayload(Map<String, ClientMeta> clientMeta, boolean syncAll, String targetDimension, boolean silent) {
        this(clientMeta, 0, 0, syncAll, targetDimension, silent);
    }

    public SyncRequestPayload(Map<String, ClientMeta> clientMeta, int partIndex, int totalParts,
            boolean syncAll, String targetDimension) {
        this(clientMeta, partIndex, totalParts, syncAll, targetDimension, false);
    }

    public SyncRequestPayload(Map<String, ClientMeta> clientMeta, int partIndex, int totalParts,
            boolean syncAll, String targetDimension, boolean silent) {
        this.clientMeta = clientMeta;
        this.partIndex = partIndex;
        this.totalParts = totalParts;
        this.syncAll = syncAll;
        this.targetDimension = targetDimension != null ? targetDimension : "";
        this.silent = silent;
    }

    public Map<String, ClientMeta> clientMeta() { return clientMeta; }
    public int partIndex() { return partIndex; }
    public int totalParts() { return totalParts; }
    public boolean syncAll() { return syncAll; }
    public String targetDimension() { return targetDimension; }
    public boolean silent() { return silent; }

    private static final int ESTIMATED_ENTRY_BYTES = 100;

    public static SyncRequestPayload[] split(Map<String, ClientMeta> metaMap, boolean syncAll, String targetDimension) {
        return split(metaMap, syncAll, targetDimension, false);
    }

    public static SyncRequestPayload[] split(Map<String, ClientMeta> metaMap, boolean syncAll, String targetDimension, boolean silent) {
        int maxEntriesPerPart = Math.max(1, MAX_PAYLOAD_BYTES / ESTIMATED_ENTRY_BYTES);
        if (metaMap.size() <= maxEntriesPerPart) {
            return new SyncRequestPayload[] { new SyncRequestPayload(metaMap, syncAll, targetDimension, silent) };
        }

        List<Map.Entry<String, ClientMeta>> entries = new ArrayList<>(metaMap.entrySet());
        int totalParts = (entries.size() + maxEntriesPerPart - 1) / maxEntriesPerPart;
        SyncRequestPayload[] parts = new SyncRequestPayload[totalParts];

        for (int i = 0; i < totalParts; i++) {
            int start = i * maxEntriesPerPart;
            int end = Math.min(start + maxEntriesPerPart, entries.size());
            Map<String, ClientMeta> partMap = new HashMap<>();
            for (int j = start; j < end; j++) {
                partMap.put(entries.get(j).getKey(), entries.get(j).getValue());
            }
            parts[i] = new SyncRequestPayload(partMap, i, totalParts, syncAll, targetDimension, silent);
        }
        return parts;
    }

    public static void write(FriendlyByteBuf buf, SyncRequestPayload payload) {
        buf.writeInt(payload.clientMeta().size());
        for (var entry : payload.clientMeta().entrySet()) {
            buf.writeUtf(entry.getKey());
            buf.writeLong(entry.getValue().timestampSeconds());
            buf.writeUtf(entry.getValue().hash());
        }
        buf.writeBoolean(payload.totalParts() > 1);
        if (payload.totalParts() > 1) {
            buf.writeInt(payload.partIndex());
            buf.writeInt(payload.totalParts());
        }
        buf.writeBoolean(payload.syncAll());
        if (!payload.syncAll()) {
            buf.writeUtf(payload.targetDimension());
        }
        buf.writeBoolean(payload.silent());
    }

    public static SyncRequestPayload read(FriendlyByteBuf buf) {
        int size = buf.readInt();
        Map<String, ClientMeta> metaMap = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String path = buf.readUtf();
            long timestampSeconds = buf.readLong();
            String hash = buf.readUtf();
            metaMap.put(path, new ClientMeta(timestampSeconds, hash));
        }

        int partIndex = 0;
        int totalParts = 0;
        if (buf.readableBytes() > 0) {
            boolean isSplit = buf.readBoolean();
            if (isSplit) {
                partIndex = buf.readInt();
                totalParts = buf.readInt();
            }
        }

        boolean syncAll = false;
        String targetDimension = "";
        if (buf.readableBytes() > 0) {
            syncAll = buf.readBoolean();
            if (!syncAll && buf.readableBytes() > 0) {
                targetDimension = buf.readUtf();
            }
        } else if (metaMap.isEmpty()) {
            syncAll = true;
        }

        boolean silent = false;
        if (buf.readableBytes() > 0) {
            silent = buf.readBoolean();
        }

        return new SyncRequestPayload(metaMap, partIndex, totalParts, syncAll, targetDimension, silent);
    }
}
