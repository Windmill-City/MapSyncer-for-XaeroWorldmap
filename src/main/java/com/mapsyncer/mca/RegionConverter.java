package com.mapsyncer.mca;

import com.mapsyncer.mca.RegionScanner.Region;
import com.mapsyncer.util.PathUtils;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class RegionConverter {

    private static final Logger LOGGER = LogManager.getLogger(RegionConverter.class);

    public static void convert(Region entry) throws IOException {
        String dimId = PathUtils.getDimId(entry.level());
        Plan plan = Plan.getPlan(dimId);
        if (!plan.surface() && plan.caves().isEmpty()) {
            LOGGER.debug("No layer plan for dimension {}, skipping region ({}, {})", dimId, entry.X(), entry.Z());
            return;
        }

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
