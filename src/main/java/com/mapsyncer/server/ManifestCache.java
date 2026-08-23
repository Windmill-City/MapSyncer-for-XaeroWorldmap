package com.mapsyncer.server;

import com.mapsyncer.util.ClientMeta;
import com.mapsyncer.util.DimensionPathMapping;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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

    private ManifestCache() {}

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

    public Map<String, Long> buildManifest(
            Path absCacheDir,
            Set<String> requestedDimensions,
            DimensionPathMapping dimMapping,
            GenerationCache genCache) {
        if (!isValid(absCacheDir)) {
            synchronized (this) {
                if (!isValid(absCacheDir)) {
                    rebuild(absCacheDir, dimMapping, genCache);
                }
            }
        }

        Map<String, Long> snapshot = manifest;
        Map<String, Long> filtered = new HashMap<>();
        for (Map.Entry<String, Long> entry : snapshot.entrySet()) {
            String[] pathParts = entry.getKey().split("[/\\\\]");
            String xaeroDim = pathParts.length > 1 ? pathParts[0] : "unknown";
            if (requestedDimensions.contains(xaeroDim)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    private boolean isValid(Path absCacheDir) {
        return valid && builtCacheDir != null && absCacheDir.equals(builtCacheDir);
    }

    private void rebuild(Path absCacheDir, DimensionPathMapping dimMapping, GenerationCache genCache) {
        Map<String, Long> rebuilt = new HashMap<>();
        Map<String, Path> rebuiltPaths = new HashMap<>();
        try (Stream<Path> stream = Files.walk(absCacheDir)) {
            stream.filter(p -> p.toString().endsWith(".zip")).forEach(zipPath -> {
                String normalizedPath = ServerSyncHandlerLogic.toNormalizedServerPath(absCacheDir, zipPath, dimMapping);
                ClientMeta meta = genCache.getMeta(normalizedPath);
                long timestamp = meta != null ? meta.timestampSeconds() : System.currentTimeMillis() / 1000;
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
