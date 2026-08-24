package com.mapsyncer.util;

import java.nio.file.Path;
import net.minecraft.world.level.Level;

public final class PathUtils {

    public static final Path CACHE_DIR = Path.of("mapsyncer");

    public static String getDimId(Level level) {
        return level.dimension().location().toString();
    }

    public static Path getDimDirServer(String dimId) {
        var normalized = dimId.replace(':', '$').replace('/', '%');
        return CACHE_DIR.resolve(normalized);
    }

    public static int getCaveByDir(Path relativePath) {
        int count = relativePath.getNameCount();
        for (int i = 0; i < count - 1; i++) {
            if ("caves".equals(relativePath.getName(i).toString())) {
                return Integer.parseInt(relativePath.getName(i + 1).toString());
            }
        }
        return Integer.MAX_VALUE;
    }

    public static int[] getCoordByZip(Path zipPath) {
        String fileName = zipPath.getFileName().toString();
        // Remove .zip
        fileName = fileName.substring(0, fileName.length() - 4);
        String[] coords = fileName.split("_");
        return new int[] {Integer.parseInt(coords[0]), Integer.parseInt(coords[1])};
    }
}
