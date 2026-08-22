package com.mapsyncer.network.payload;

import com.mapsyncer.util.ClientMeta;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;

public class SyncRequestPayload {
    private final Map<String, ClientMeta> clientMeta;
    private final int partIndex;
    private final int totalParts;
    private final boolean syncAll;
    private final String targetDimension;
    private final boolean silent;

    public SyncRequestPayload(
            Map<String, ClientMeta> clientMeta, boolean syncAll, String targetDimension, boolean silent) {
        this(clientMeta, 0, 0, syncAll, targetDimension, silent);
    }

    public SyncRequestPayload(
            Map<String, ClientMeta> clientMeta,
            int partIndex,
            int totalParts,
            boolean syncAll,
            String targetDimension,
            boolean silent) {
        this.clientMeta = clientMeta;
        this.partIndex = partIndex;
        this.totalParts = totalParts;
        this.syncAll = syncAll;
        this.targetDimension = targetDimension != null ? targetDimension : "";
        this.silent = silent;
    }

    public Map<String, ClientMeta> clientMeta() {
        return clientMeta;
    }

    public int partIndex() {
        return partIndex;
    }

    public int totalParts() {
        return totalParts;
    }

    public boolean syncAll() {
        return syncAll;
    }

    public String targetDimension() {
        return targetDimension;
    }

    public boolean silent() {
        return silent;
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
        boolean isSplit = buf.readBoolean();
        if (isSplit) {
            partIndex = buf.readInt();
            totalParts = buf.readInt();
        }

        boolean syncAll = buf.readBoolean();
        String targetDimension = "";
        if (!syncAll) {
            targetDimension = buf.readUtf();
        }

        boolean silent = buf.readBoolean();

        return new SyncRequestPayload(metaMap, partIndex, totalParts, syncAll, targetDimension, silent);
    }
}
