package com.mapsyncer.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

public record SyncResponsePayload(List<RegionData> chunks, boolean isComplete) {

    public static void write(FriendlyByteBuf buf, SyncResponsePayload payload) {
        buf.writeInt(payload.chunks().size());
        for (RegionData chunk : payload.chunks()) {
            RegionData.write(buf, chunk);
        }
        buf.writeBoolean(payload.isComplete());
    }

    public static SyncResponsePayload read(FriendlyByteBuf buf) {
        int size = buf.readInt();
        List<RegionData> chunks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            chunks.add(RegionData.read(buf));
        }
        boolean isComplete = buf.readBoolean();
        return new SyncResponsePayload(chunks, isComplete);
    }
}
