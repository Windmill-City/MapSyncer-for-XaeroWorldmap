package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionInfo;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverter.CaveModeParams;
import com.mapsyncer.mca.convert.scan.RegionScanPass;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ScanPlanner {

    private static final int CAVE_LAYER_DEPTH = 15;

    public static List<RegionScanPass> plan(LayerPlan plan, DimensionInfo info) {
        List<RegionScanPass> passes = new ArrayList<>();
        Set<Integer> seenLayers = new LinkedHashSet<>();

        if (plan.surface()) {
            addSurfacePass(passes, seenLayers, info);
        }

        for (int caveStart : plan.caves()) {
            addCaveStartPass(passes, seenLayers, caveStart);
        }

        if (passes.isEmpty()) {
            addSurfacePass(passes, seenLayers, info);
        }

        return List.copyOf(passes);
    }

    public static int countPasses(LayerPlan plan, DimensionInfo info) {
        return plan(plan, info).size();
    }

    private static void addSurfacePass(List<RegionScanPass> passes, Set<Integer> seenLayers, DimensionInfo info) {
        if (!seenLayers.add(Integer.MAX_VALUE)) {
            return;
        }
        RegionScanPass.ScanVerticalBounds bounds = info.hasUpperZone()
                ? RegionScanPass.ScanVerticalBounds.aboveY(info.logicalTopY(), info.maxY())
                : RegionScanPass.ScanVerticalBounds.fullColumn(info.minY(), info.maxY());
        passes.add(new RegionScanPass(Integer.MAX_VALUE, LightMode.SURFACE, CaveModeParams.NONE, bounds));
    }

    private static void addCaveStartPass(
            List<RegionScanPass> passes, Set<Integer> seenLayers, int caveStart) {
        int layer = caveLayerFromStart(caveStart);
        if (!seenLayers.add(layer)) {
            return;
        }
        passes.add(new RegionScanPass(
                layer,
                LightMode.CAVE,
                new CaveModeParams(caveStart, CAVE_LAYER_DEPTH),
                RegionScanPass.ScanVerticalBounds.unbounded()));
    }

    private static int caveLayerFromStart(int caveStart) {
        return caveStart >> 4;
    }
}
