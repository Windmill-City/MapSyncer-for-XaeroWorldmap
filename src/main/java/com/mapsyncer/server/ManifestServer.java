package com.mapsyncer.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mapsyncer.util.PathMapping;

public class ManifestServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManifestServer.class);

    private static final ManifestServer INSTANCE = new ManifestServer();

    private record ManifestEntry(Path zipPath, long timestampMillis) {}

    private volatile Map<String, ManifestEntry> manifest = Map.of();

    private volatile @Nullable Path builtCacheDir;

    private volatile boolean valid = false;

    public static ManifestServer get() {
        return INSTANCE;
    }

    public Map<String, Long> build(Path absCacheDir) {
        if (!isValid(absCacheDir)) {
            synchronized (this) {
                if (!isValid(absCacheDir)) {
                    _build(absCacheDir);
                }
            }
        }
        Map<String, Long> timestamps = new HashMap<>(manifest.size());
        manifest.forEach((path, entry) -> timestamps.put(path, entry.timestampMillis()));
        return timestamps;
    }

    private boolean isValid(Path absCacheDir) {
        return valid && absCacheDir.equals(builtCacheDir);
    }

    private void _build(Path absCacheDir) {
        Map<String, ManifestEntry> rebuilt = new HashMap<>();
        try (Stream<Path> stream = Files.walk(absCacheDir)) {
            stream.filter(p -> p.toString().endsWith(".zip")).forEach(zipPath -> {
                String normalizedPath = toNormalizedServerPath(absCacheDir, zipPath);
                long timestamp = readMtimeMillis(zipPath);
                rebuilt.put(normalizedPath, new ManifestEntry(zipPath, timestamp));
            });
        } catch (IOException e) {
            LOGGER.error("Failed to walk cache directory while building manifest", e);
        }
        builtCacheDir = absCacheDir;
        manifest = rebuilt;
        valid = true;
        LOGGER.info("Manifest cache built for {} with {} entries", absCacheDir, rebuilt.size());
    }

    private static long readMtimeMillis(Path zipPath) {
        try {
            FileTime mtime = Files.getLastModifiedTime(zipPath);
            return mtime.toMillis();
        } catch (IOException e) {
            LOGGER.warn("Failed to read mtime for {}, using current time", zipPath);
            return System.currentTimeMillis();
        }
    }

    public @Nullable Path resolveZipPath(String normalizedPath) {
        ManifestEntry entry = manifest.get(normalizedPath);
        return entry == null ? null : entry.zipPath();
    }

    public @Nullable Long getTimestamp(String normalizedPath) {
        ManifestEntry entry = manifest.get(normalizedPath);
        return entry == null ? null : entry.timestampMillis();
    }

    private String toNormalizedServerPath(Path absCacheDir, Path zipPath) {
        String relativePath = absCacheDir.relativize(zipPath).toString();
        String normalizedPath = relativePath.replace(".zip", "").replace("\\", "/");

        String[] parts = normalizedPath.split("[/\\\\]");
        if (parts.length < 2) {
            return normalizedPath;
        }

        try {
            String dim = parts[0];
            int caveLayer = Integer.MAX_VALUE;
            int coordsIndex = parts.length - 1;
            for (int i = 1; i < coordsIndex; i++) {
                if ("caves".equals(parts[i]) && i + 1 < coordsIndex) {
                    caveLayer = Integer.parseInt(parts[i + 1]);
                    break;
                }
            }

            String[] coords = parts[coordsIndex].split("_");
            if (coords.length != 2) {
                return normalizedPath;
            }

            return PathMapping.toRelativeRegionPath(
                    dim, caveLayer, Integer.parseInt(coords[0]), Integer.parseInt(coords[1]));
        } catch (NumberFormatException e) {
            LOGGER.warn("Skipping unparseable cache path: {}", relativePath);
            return normalizedPath;
        }
    }

    public void invalidate() {
        valid = false;
        manifest = Map.of();
        builtCacheDir = null;
        LOGGER.debug("ManifestCache invalidated");
    }
}
