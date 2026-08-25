package com.mapsyncer.mca;

import com.mapsyncer.mca.RegionScanner.Region;
import com.mapsyncer.util.PathUtils;
import java.io.IOException;

public final class RegionConverter {

    public static void convert(Region entry, Plan plan) throws IOException {
        String dimId = PathUtils.getDimId(entry.level());
        if (plan.surface()) {
            writeSurfaceLayer(entry, dimId);
        }
        for (int caveY : plan.caves()) {
            writeCaveLayer(entry, dimId, caveY);
        }
    }

    private static void writeSurfaceLayer(Region entry, String dimId) throws IOException {
        // TODO: RegionBuilder with top-down scan, RegionRef.SURFACE_CAVE,
        //       surface parametres in savePixel, write via XaeroWriter
        throw new UnsupportedOperationException("not implemented yet");
    }

    private static void writeCaveLayer(Region entry, String dimId, int caveY) throws IOException {
        // TODO: RegionBuilder with cave scan (caveStart = caveY, depth = RegionBuilder.CAVE_DEPTH),
        //       cave parametres in savePixel, write via XaeroWriter
        throw new UnsupportedOperationException("not implemented yet");
    }
}
