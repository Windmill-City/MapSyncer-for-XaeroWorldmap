package com.mapsyncer.mca;

public enum LightMode {
    SURFACE,

    CAVE;

    public byte calculateEffectiveLight(
            byte blockLight,
            byte skyLight,
            boolean hasSkyAccess,
            boolean hasOverlay,
            boolean isGlowing,
            boolean worldHasSkylight) {

        if (isGlowing) {
            return 15;
        }

        switch (this) {
            case SURFACE:
                return blockLight;

            case CAVE:
                if (blockLight >= 15) {
                    return blockLight;
                }

                byte effectiveSkyLight = (hasSkyAccess && worldHasSkylight) ? 15 : skyLight;

                if (!hasOverlay && effectiveSkyLight > blockLight) {
                    return effectiveSkyLight;
                }

                return blockLight;

            default:
                return blockLight;
        }
    }
}
