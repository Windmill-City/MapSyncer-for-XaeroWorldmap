package com.mapsyncer.util;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PathMapping {

    private static final Logger LOGGER = LoggerFactory.getLogger(PathMapping.class);

    private static final String OVERWORLD_XAERO = "null";
    private static final String NETHER_XAERO = "DIM-1";
    private static final String END_XAERO = "DIM1";

    public static String toXaeroDimension(String dimPath) {
        if (dimPath == null || dimPath.isEmpty()) {
            return OVERWORLD_XAERO;
        }

        String normalized = normalize(dimPath);

        if (OVERWORLD_XAERO.equals(normalized)
                || NETHER_XAERO.equals(normalized)
                || END_XAERO.equals(normalized)
                || normalized.contains("$")
                || normalized.startsWith("DIM")) {
            return normalized;
        }

        switch (normalized) {
            case "overworld":
                return OVERWORLD_XAERO;
            case "the_nether":
                return NETHER_XAERO;
            case "the_end":
                return END_XAERO;
            default:
        }

        String[] parts = normalized.split(":", 2);
        String namespace = parts.length == 2 ? parts[0] : "minecraft";
        String path = parts.length == 2 ? parts[1] : normalized;
        return namespace + "$" + xaeroEscape(path);
    }

    public static String toRelativeRegionPath(String dimPath, int caveLayer, int regionX, int regionZ) {
        String xaeroDim = toXaeroDimension(dimPath);
        if (caveLayer == Integer.MAX_VALUE) {
            return xaeroDim + "/" + regionX + "_" + regionZ;
        }
        return xaeroDim + "/caves/" + caveLayer + "/" + regionX + "_" + regionZ;
    }

    public static String toServerDimension(String dimPath) {        if (dimPath == null || dimPath.isEmpty()) {
            return "overworld";
        }

        if (OVERWORLD_XAERO.equals(dimPath)) {
            return "overworld";
        }
        if (NETHER_XAERO.equals(dimPath)) {
            return "the_nether";
        }
        if (END_XAERO.equals(dimPath)) {
            return "the_end";
        }

        int dollarIndex = dimPath.indexOf('$');
        if (dollarIndex > 0) {
            String namespace = dimPath.substring(0, dollarIndex);
            String path = xaeroUnescape(dimPath.substring(dollarIndex + 1));
            return namespace + ":" + path;
        }

        return normalize(dimPath);
    }

    public static String getFriendlyName(String dimPath) {
        String serverDim = toServerDimension(dimPath);
        String normalized = normalize(serverDim);
        if (normalized.contains(":")) {
            return normalized;
        }
        return "minecraft:" + normalized;
    }

    public static boolean isNether(String dimPath) {
        String normalized = normalize(dimPath);
        return "the_nether".equals(normalized) || NETHER_XAERO.equals(normalized);
    }

    public static @Nullable Path detectRegionDir(Path worldRoot, String dimPath) {
        if (worldRoot == null || !Files.exists(worldRoot)) {
            return null;
        }

        String normalized = normalize(dimPath);

        if (normalized.contains(":")) {
            Path regionDir = worldRoot
                    .resolve("dimensions")
                    .resolve(normalized.replace(':', '/'))
                    .resolve("region");
            if (Files.exists(regionDir)) {
                return regionDir;
            }
        }

        LOGGER.warn("Could not detect region directory for dimension: {}", normalized);
        return null;
    }

    private static String normalize(String dimPath) {
        if (dimPath == null || dimPath.isEmpty()) {
            return "overworld";
        }
        if (dimPath.startsWith("minecraft:")) {
            dimPath = dimPath.substring("minecraft:".length());
        }
        if ("null".equals(dimPath)) {
            return "overworld";
        }
        return dimPath;
    }

    private static String xaeroEscape(String path) {
        return replaceTrailingDots(path.replace('/', '%'), ',');
    }

    private static String xaeroUnescape(String path) {
        return path.replace('%', '/').replace(',', '.');
    }

    private static String replaceTrailingDots(String value, char replacement) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '.') {
            end--;
        }
        if (end == value.length()) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value);
        for (int i = sb.length() - 1; i >= end; i--) {
            sb.setCharAt(i, replacement);
        }
        return sb.toString();
    }
}
