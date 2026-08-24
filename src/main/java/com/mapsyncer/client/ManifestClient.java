package com.mapsyncer.client;

import com.mapsyncer.network.RegionRef;
import com.mapsyncer.util.RegionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ManifestClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManifestClient.class);

    public static void getManifestAsync(Set<String> dimIds, Consumer<Map<RegionRef, Long>> callback) {
        try {
            Util.ioPool().execute(() -> {
                try {
                    callback.accept(getManifest(dimIds));
                } catch (Exception e) {
                    LOGGER.error("Exception occurred while processing client manifest", e);
                }
            });
        } catch (RejectedExecutionException e) {
            LOGGER.error("Scan executor rejected task, executor shutdown?", e);
            callback.accept(Collections.emptyMap());
        }
    }

    private static Map<RegionRef, Long> getManifest(Set<String> dimIds) {
        Map<RegionRef, Long> manifest = new HashMap<>();

        Path serverDir = XaeroBridge.getCurrentServerDirectory();
        if (serverDir == null || !Files.exists(serverDir)) {
            LOGGER.info("Xaero server directory unavailable ({}), will request all regions from server", serverDir);
            return manifest;
        }

        for (String dimId : dimIds) {
            String xaeroDim = XaeroBridge.getDimensionName(dimId);
            if (xaeroDim == null) {
                LOGGER.debug("No Xaero dimension name for {}, skipping", dimId);
                continue;
            }
            Path dimDir = serverDir.resolve(xaeroDim);
            if (!Files.isDirectory(dimDir)) {
                LOGGER.debug("No local map directory for dimension {} ({}), skipping", dimId, xaeroDim);
                continue;
            }

            List<Path> zipFiles;
            try (Stream<Path> walk = Files.walk(dimDir)) {
                zipFiles = walk.filter(p -> p.toString().endsWith(".zip")).toList();
            } catch (IOException e) {
                LOGGER.error("Failed to walk map directory {}", dimDir, e);
                continue;
            }

            for (Path zipPath : zipFiles) {
                try {
                    RegionRef ref = buildKey(dimId, dimDir, zipPath);
                    long timestampMillis = getFileModificationTime(zipPath);
                    LOGGER.debug("Region {}: ts={}ms (mtime)", ref, timestampMillis);
                    manifest.put(ref, timestampMillis);
                } catch (Exception e) {
                    LOGGER.error("Failed to scan region file: {}", zipPath, e);
                    continue;
                }
            }
        }

        LOGGER.info("Found {} regions with metadata", manifest.size());

        return manifest;
    }

    private static long getFileModificationTime(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            FileTime time = attrs.lastModifiedTime();
            return time.toMillis();
        } catch (IOException e) {
            LOGGER.error("Failed to get modification time for {}", path, e);
            return 0;
        }
    }

    private static RegionRef buildKey(String dimId, Path dimDir, Path zipPath) {
        String relative = dimDir.relativize(zipPath).toString().replace("\\", "/");
        int caveLayer = RegionKey.caveLayerFromRelative(relative);
        int[] coords = RegionKey.coordsFromZipFileName(zipPath);
        return new RegionRef(dimId, caveLayer, coords[0], coords[1]);
    }
}
