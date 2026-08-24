package com.mapsyncer.mca.convert;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.Plan;
import com.mapsyncer.mca.RegionScanner.WorldBounds;
import com.mapsyncer.mca.convert.io.McaRegionLoader;
import com.mapsyncer.mca.convert.io.McaRegionLoader.PassMapData;
import com.mapsyncer.mca.convert.io.XaeroBinaryWriter;
import com.mapsyncer.mca.convert.io.XaeroWriter;
import com.mapsyncer.mca.convert.model.MapRegionData;
import com.mapsyncer.network.RegionData;
import com.mapsyncer.network.RegionRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Pipeline {

    public static void convert(
            String dimId,
            Path mcaPath,
            int regionX,
            int regionZ,
            WorldBounds bounds,
            Plan plan,
            BlockPropertyLookup blockLookup)
            throws IOException {

        if (!Files.exists(mcaPath) || plan.caveStarts().isEmpty()) {
            return;
        }

        List<PassMapData> loaded = McaRegionLoader.load(
                mcaPath, bounds.minY(), bounds.maxY(), bounds.hasSkylight(), blockLookup, plan);

        for (PassMapData passData : loaded) {
            MapRegionData regionData = passData.data();
            if (!regionData.hasAnyMapData()) {
                continue;
            }
            byte[] xaeroData = XaeroBinaryWriter.serialize(regionData, bounds.minY(), blockLookup);
            XaeroWriter.writeRegionFile(new RegionData(
                    new RegionRef(dimId, Plan.caveLayer(passData.cave()), regionX, regionZ),
                    xaeroData));
        }
    }
}
