package com.mapsyncer.mca;

public record DimensionInfo(boolean hasSkylight, boolean hasCeiling, int minY, int height, int logicalHeight) {

    public int maxY() {
        return minY + height;
    }

    public int logicalTopY() {
        return minY + logicalHeight - 1;
    }

    public boolean hasUpperZone() {
        return hasCeiling && logicalHeight < height;
    }

    public static DimensionInfo overworld() {
        return new DimensionInfo(true, false, -64, 384, 384);
    }

    public static DimensionInfo nether() {
        return new DimensionInfo(false, true, 0, 256, 128);
    }

    public static DimensionInfo theEnd() {
        return new DimensionInfo(false, false, 0, 256, 256);
    }

    public static DimensionInfo fromDimensionId(String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty()) {
            return overworld();
        }

        String normalized = dimensionId.replace("minecraft:", "").toLowerCase();

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
        return String.format(
                "DimensionInfo[hasSkylight=%s, hasCeiling=%s, minY=%d, height=%d, maxY=%d]",
                hasSkylight, hasCeiling, minY, height, maxY());
    }
}
