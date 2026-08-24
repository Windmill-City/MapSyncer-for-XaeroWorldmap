package com.mapsyncer.mca;

public final class LightUtil {

    public static byte getLight(
            boolean isSurface,
            byte blockLight,
            byte skyLight,
            boolean hasSkyAccess,
            boolean hasOverlay,
            boolean isGlowing,
            boolean worldHasSkylight) {

        if (isGlowing) {
            return Constants.MAX_LIGHT_LEVEL;
        }

        if (isSurface) {
            return blockLight;
        }

        if (blockLight >= Constants.MAX_LIGHT_LEVEL) {
            return blockLight;
        }

        byte effectiveSkyLight = (hasSkyAccess && worldHasSkylight) ? Constants.MAX_LIGHT_LEVEL : skyLight;

        if (!hasOverlay && effectiveSkyLight > blockLight) {
            return effectiveSkyLight;
        }

        return blockLight;
    }
}
