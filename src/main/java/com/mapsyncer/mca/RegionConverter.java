package com.mapsyncer.mca;

import com.mapsyncer.mca.convert.Pipeline;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class RegionConverter {

    public static void convert(RegionScanner.Region entry, BlockPropertyLookup blockLookup) throws IOException {
        RegionScanner.Bounds bounds = entry.bounds();
        Plan plan = ConvertPlans.getPlan(entry.dimId());
        if (plan.caveStarts().isEmpty()) {
            return;
        }

        Path mcaPath = resolveRegionPath(
                entry.regionDir(), entry.regionX(), entry.regionZ());
        if (mcaPath == null) {
            return;
        }

        Pipeline.convert(
                entry.dimId(), mcaPath, entry.regionX(), entry.regionZ(), bounds, plan, blockLookup);
    }

    private static Path resolveRegionPath(Path regionDir, int regionX, int regionZ) {
        Path mcaPath = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        if (Files.exists(mcaPath)) {
            return mcaPath;
        }
        Path mcrPath = regionDir.resolve("r." + regionX + "." + regionZ + ".mcr");
        return Files.exists(mcrPath) ? mcrPath : null;
    }
}
