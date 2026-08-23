package com.mapsyncer.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

public class MapRequestPayload {
    private final List<RegionRef> regions;
    private final int partIndex;
    private final int totalParts;

    public MapRequestPayload(List<RegionRef> regions) {
        this(regions, 0, 0);
    }

    public MapRequestPayload(List<RegionRef> regions, int partIndex, int totalParts) {
        this.regions = regions;
        this.partIndex = partIndex;
        this.totalParts = totalParts;
    }

    public List<RegionRef> regions() {
        return regions;
    }

    public int partIndex() {
        return partIndex;
    }

    public int totalParts() {
        return totalParts;
    }

    public static void write(FriendlyByteBuf buf, MapRequestPayload payload) {
        buf.writeInt(payload.regions().size());
        for (RegionRef region : payload.regions()) {
            RegionRef.write(buf, region);
        }
        buf.writeBoolean(payload.totalParts() > 1);
        if (payload.totalParts() > 1) {
            buf.writeInt(payload.partIndex());
            buf.writeInt(payload.totalParts());
        }
    }

    public static MapRequestPayload read(FriendlyByteBuf buf) {
        int size = buf.readInt();
        List<RegionRef> regions = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            regions.add(RegionRef.read(buf));
        }

        int partIndex = 0;
        int totalParts = 0;
        boolean isSplit = buf.readBoolean();
        if (isSplit) {
            partIndex = buf.readInt();
            totalParts = buf.readInt();
        }

        return new MapRequestPayload(regions, partIndex, totalParts);
    }
}
