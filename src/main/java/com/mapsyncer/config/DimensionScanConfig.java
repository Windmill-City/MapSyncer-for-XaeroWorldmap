package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionInfo;

public record DimensionScanConfig(String dimension, LayerPlan layerPlan, DimensionInfo dimTypeInfo) {}
