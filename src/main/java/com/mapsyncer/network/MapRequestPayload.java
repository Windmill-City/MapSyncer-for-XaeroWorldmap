package com.mapsyncer.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

public class MapRequestPayload {
    private final List<RegionRef> regions;

    public MapRequestPayload(List<RegionRef> regions) {
        this.regions = regions;
    }

    public List<RegionRef> regions() {
        return regions;
    }

    public static void write(FriendlyByteBuf buf, MapRequestPayload payload) {
        buf.writeInt(payload.regions().size());
        for (RegionRef region : payload.regions()) {
            RegionRef.write(buf, region);
        }
    }

    public static MapRequestPayload read(FriendlyByteBuf buf) {
        int size = buf.readInt();
        List<RegionRef> regions = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            regions.add(RegionRef.read(buf));
        }

        return new MapRequestPayload(regions);
    }
}
