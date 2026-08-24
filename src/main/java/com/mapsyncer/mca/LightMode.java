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
            return Constants.MAX_LIGHT_LEVEL;
        }

        switch (this) {
            case SURFACE:
                return blockLight;

            case CAVE:
                if (blockLight >= Constants.MAX_LIGHT_LEVEL) {
                    return blockLight;
                }

                byte effectiveSkyLight = (hasSkyAccess && worldHasSkylight) ? Constants.MAX_LIGHT_LEVEL : skyLight;

                if (!hasOverlay && effectiveSkyLight > blockLight) {
                    return effectiveSkyLight;
                }

                return blockLight;

            default:
                return blockLight;
        }
    }
}
