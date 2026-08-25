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
        // TODO: build region.xaero bytes via RegionBuilder, wrap in RegionData, write via XaeroWriter
        throw new UnsupportedOperationException("not implemented yet");
    }
}
