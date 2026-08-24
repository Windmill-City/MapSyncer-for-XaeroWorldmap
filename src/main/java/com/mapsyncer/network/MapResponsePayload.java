package com.mapsyncer.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

public record MapResponsePayload(List<RegionData> chunks) {

    public static void write(FriendlyByteBuf buf, MapResponsePayload payload) {
        buf.writeInt(payload.chunks().size());
        for (RegionData chunk : payload.chunks()) {
            RegionData.write(buf, chunk);
        }
    }

    public static MapResponsePayload read(FriendlyByteBuf buf) {
        int size = buf.readInt();
        List<RegionData> chunks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            chunks.add(RegionData.read(buf));
        }
        return new MapResponsePayload(chunks);
    }
}
