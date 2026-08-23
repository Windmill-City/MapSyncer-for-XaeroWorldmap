package com.mapsyncer.util;

import java.nio.file.Path;

public final class RegionKey {

    public static int caveLayerFromRelative(String relativePath) {
        String[] parts = relativePath.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("caves".equals(parts[i])) {
                try {
                    return Integer.parseInt(parts[i + 1]);
                } catch (NumberFormatException e) {
                    return Integer.MAX_VALUE;
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    public static int[] coordsFromZipFileName(Path zipPath) {
        String fileName = zipPath.getFileName().toString();
        if (fileName.endsWith(".zip")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }
        String[] coords = fileName.split("_");
        return new int[] { Integer.parseInt(coords[0]), Integer.parseInt(coords[1]) };
    }
}
