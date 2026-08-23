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

public class ManifestCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManifestCache.class);

    private static volatile @Nullable ManifestCache instance;

    private volatile Map<String, Long> manifest = Map.of();

    private volatile Map<String, Path> zipPaths = Map.of();

    private volatile @Nullable Path builtCacheDir;

    private volatile boolean valid = false;

    public static ManifestCache getInstance() {
        ManifestCache current = instance;
        if (current == null) {
            synchronized (ManifestCache.class) {
                current = instance;
                if (current == null) {
                    current = new ManifestCache();
                    instance = current;
                }
            }
        }
        return current;
    }

    public Map<String, Long> buildFullManifest(Path absCacheDir) {
        if (!isValid(absCacheDir)) {
            synchronized (this) {
                if (!isValid(absCacheDir)) {
                    rebuild(absCacheDir);
                }
            }
        }
        return new HashMap<>(manifest);
    }

    private boolean isValid(Path absCacheDir) {
        return valid && builtCacheDir != null && absCacheDir.equals(builtCacheDir);
    }

    private void rebuild(Path absCacheDir) {
        Map<String, Long> rebuilt = new HashMap<>();
        Map<String, Path> rebuiltPaths = new HashMap<>();
        try (Stream<Path> stream = Files.walk(absCacheDir)) {
            stream.filter(p -> p.toString().endsWith(".zip")).forEach(zipPath -> {
                String normalizedPath = ServerSyncHandlerLogic.toNormalizedServerPath(absCacheDir, zipPath);
                long timestamp = readMtimeMillis(zipPath);
                rebuilt.put(normalizedPath, timestamp);
                rebuiltPaths.put(normalizedPath, zipPath);
            });
        } catch (IOException e) {
            LOGGER.error("Failed to walk cache directory while building manifest", e);
        }
        builtCacheDir = absCacheDir;
        manifest = rebuilt;
        zipPaths = rebuiltPaths;
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
        return zipPaths.get(normalizedPath);
    }

    public @Nullable Long getTimestamp(String normalizedPath) {
        return manifest.get(normalizedPath);
    }

    public void invalidate() {
        valid = false;
        manifest = Map.of();
        zipPaths = Map.of();
        builtCacheDir = null;
        LOGGER.debug("ManifestCache invalidated");
    }
}
