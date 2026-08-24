package com.mapsyncer.mca;

import com.mapsyncer.mca.convert.Pipeline;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class RegionConverter {

    public static final int CHUNKS_PER_REGION = 32;

    public static void convert(RegionScanner.RegionEntry entry, BlockPropertyLookup blockLookup)
            throws IOException {
        RegionScanner.WorldBounds bounds = entry.bounds();
        Plan plan = ConvertPlans.getPlan(entry.dimId());
        if (plan.caveStarts().isEmpty()) {
            return;
        }

        Path mcaPath = resolveRegionPath(
                entry.regionDir(), entry.coords().x(), entry.coords().z());
        if (mcaPath == null) {
            return;
        }

        Pipeline.convert(
                entry.dimId(), mcaPath, entry.coords().x(), entry.coords().z(), bounds, plan, blockLookup);
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
