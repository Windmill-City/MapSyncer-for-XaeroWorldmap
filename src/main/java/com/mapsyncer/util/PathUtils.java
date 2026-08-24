package com.mapsyncer.util;

import com.mapsyncer.network.RegionRef;
import java.nio.file.Path;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class PathUtils {

    public static final Path CACHE_DIR = Path.of("mapsyncer");

    public static String getDimId(ResourceKey<Level> key) {
        return key.location().toString();
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

    public static Path resolveZipPath(RegionRef ref) {
        Path dimDir = getDimDirServer(ref.dimId());
        Path dstDir = ref.isSurface() ? dimDir : dimDir.resolve("caves").resolve(String.valueOf(ref.cave()));
        return dstDir.resolve(ref.regionX() + "_" + ref.regionZ() + ".zip");
    }
}
