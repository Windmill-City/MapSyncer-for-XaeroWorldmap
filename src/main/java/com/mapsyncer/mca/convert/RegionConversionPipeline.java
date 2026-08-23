package com.mapsyncer.mca.convert;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.mca.RegionConverter;
import com.mapsyncer.mca.convert.io.McaRegionLoader;
import com.mapsyncer.mca.convert.io.McaRegionLoader.PassMapData;
import com.mapsyncer.mca.convert.io.XaeroBinaryWriter;
import com.mapsyncer.mca.convert.model.MapRegionData;
import com.mapsyncer.mca.convert.scan.RegionScanPass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RegionConversionPipeline {

    private RegionConversionPipeline() {}

    public static List<RegionConverter.LayerConvertedRegion> convertMulti(
            Path mcaPath,
            int regionX,
            int regionZ,
            DimensionTypeInfo dimTypeInfo,
            List<RegionScanPass> passes,
            BlockPropertyLookup blockLookup)
            throws IOException {

        if (!Files.exists(mcaPath) || passes.isEmpty()) {
            return List.of();
        }

        List<PassMapData> loaded = McaRegionLoader.loadMulti(
                mcaPath, dimTypeInfo.minY(), dimTypeInfo.maxY(), dimTypeInfo.hasSkylight(), blockLookup, passes);

        List<RegionConverter.LayerConvertedRegion> results = new ArrayList<>();
        for (PassMapData passData : loaded) {
            MapRegionData regionData = passData.data();
            if (!regionData.hasAnyMapData()) {
                results.add(new RegionConverter.LayerConvertedRegion(regionX, regionZ, new byte[0]));
                continue;
            }
            byte[] xaeroData = XaeroBinaryWriter.serialize(regionData, dimTypeInfo.minY(), blockLookup);
            results.add(new RegionConverter.LayerConvertedRegion(regionX, regionZ, xaeroData));
        }
        return results;
    }
}
