package com.mapsyncer.mca;

public record DimensionTypeInfo(
    boolean hasSkylight,
    boolean hasCeiling,
    int minY,
    int height,
    int logicalHeight
) {

    public int maxY() {
        return minY + height;
    }

    public int logicalTopY() {
        return minY + logicalHeight - 1;
    }

    public boolean hasUpperZone() {
        return hasCeiling && logicalHeight < height;
    }

    public static DimensionTypeInfo overworld() {
        return new DimensionTypeInfo(true, false, -64, 384, 384);
    }

    public static DimensionTypeInfo nether() {
        return new DimensionTypeInfo(false, true, 0, 256, 128);
    }

    public static DimensionTypeInfo theEnd() {
        return new DimensionTypeInfo(false, false, 0, 256, 256);
    }

    public static DimensionTypeInfo fromDimensionId(String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty()) {
            return overworld();
        }

        String normalized = dimensionId
            .replace("minecraft:", "")
            .toLowerCase();

        switch (normalized) {
            case "overworld":
                return overworld();
            case "the_nether":
                return nether();
            case "the_end":
                return theEnd();
            default:

                return overworld();
        }
    }

    @Override
    public String toString() {
        return String.format("DimensionTypeInfo[hasSkylight=%s, hasCeiling=%s, minY=%d, height=%d, maxY=%d]",
            hasSkylight, hasCeiling, minY, height, maxY());
    }
}