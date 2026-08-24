package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionInfo;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverter.CaveModeParams;
import com.mapsyncer.mca.convert.scan.RegionScanPass;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ScanPlanner {

    public static List<RegionScanPass> plan(LayerPlan plan, DimensionInfo info) {
        List<RegionScanPass> passes = new ArrayList<>();

        if (plan.surface()) {
            addSurfacePass(passes, info);
        }

        for (int cave : plan.caves()) {
            addCavePass(passes, cave);
        }

        return List.copyOf(passes);
    }

    private static void addSurfacePass(List<RegionScanPass> passes, DimensionInfo info) {
        RegionScanPass.ScanVerticalBounds bounds = info.hasUpperZone()
                ? RegionScanPass.ScanVerticalBounds.aboveY(info.logicalTopY(), info.maxY())
                : RegionScanPass.ScanVerticalBounds.fullColumn(info.minY(), info.maxY());
        passes.add(new RegionScanPass(Integer.MAX_VALUE, LightMode.SURFACE, CaveModeParams.NONE, bounds));
    }

    private static void addCavePass(List<RegionScanPass> passes, int cave) {
        passes.add(new RegionScanPass(
                cave >> 4,
                LightMode.CAVE,
                new CaveModeParams(cave, 15),
                RegionScanPass.ScanVerticalBounds.unbounded()));
    }
}
