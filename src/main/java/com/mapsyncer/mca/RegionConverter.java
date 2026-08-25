package com.mapsyncer.mca;

import com.mapsyncer.mca.RegionScanner.Region;
import com.mapsyncer.util.PathUtils;
import java.io.IOException;

public final class RegionConverter {

    public static void convert(Region entry, Plan plan) throws IOException {
        String dimId = PathUtils.getDimId(entry.level());
        if (plan.surface()) {
            writeLayer(entry, dimId, RegionRef.SURFACE_CAVE);
        }
        for (int caveY : plan.caves()) {
            writeLayer(entry, dimId, caveY);
        }
    }

    private static void writeLayer(Region entry, String dimId, int cave) throws IOException {
        byte[] data = RegionBuilder.build(entry.level(), entry.regionFile(), entry.X(), entry.Z(), cave);
        RegionRef ref = new RegionRef(dimId, cave, entry.X(), entry.Z());
        XaeroWriter.writeRegionFile(new RegionData(ref, data));
    }
}
