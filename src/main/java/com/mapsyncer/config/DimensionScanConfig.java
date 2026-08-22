package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;

public record DimensionScanConfig(
    String dimension,
    LayerPlan layerPlan,
    DimensionTypeInfo dimTypeInfo
) {
}
