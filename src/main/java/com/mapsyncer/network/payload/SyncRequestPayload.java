package com.mapsyncer.network.payload;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

public class SyncRequestPayload {
    private final List<String> paths;
    private final int partIndex;
    private final int totalParts;

    public SyncRequestPayload(List<String> paths) {
        this(paths, 0, 0);
    }

    public SyncRequestPayload(List<String> paths, int partIndex, int totalParts) {
        this.paths = paths;
        this.partIndex = partIndex;
        this.totalParts = totalParts;
    }

    public List<String> paths() {
        return paths;
    }

    public int partIndex() {
        return partIndex;
    }

    public int totalParts() {
        return totalParts;
    }

    public static void write(FriendlyByteBuf buf, SyncRequestPayload payload) {
        buf.writeInt(payload.paths().size());
        for (String path : payload.paths()) {
            buf.writeUtf(path);
        }
        buf.writeBoolean(payload.totalParts() > 1);
        if (payload.totalParts() > 1) {
            buf.writeInt(payload.partIndex());
            buf.writeInt(payload.totalParts());
        }
    }

    public static SyncRequestPayload read(FriendlyByteBuf buf) {
        int size = buf.readInt();
        List<String> paths = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            paths.add(buf.readUtf());
        }

        int partIndex = 0;
        int totalParts = 0;
        boolean isSplit = buf.readBoolean();
        if (isSplit) {
            partIndex = buf.readInt();
            totalParts = buf.readInt();
        }

        return new SyncRequestPayload(paths, partIndex, totalParts);
    }
}
