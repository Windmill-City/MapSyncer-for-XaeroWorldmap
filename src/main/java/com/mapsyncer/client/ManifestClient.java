package com.mapsyncer.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mapsyncer.network.RegionRef;
import com.mapsyncer.util.PathUtils;

import net.minecraft.Util;

public class ManifestClient {

    private static final Logger LOGGER = LogManager.getLogger(ManifestClient.class);

    public static void get(Set<String> dimIds, Consumer<Map<RegionRef, Long>> callback) {
        try {
            Util.ioPool().execute(() -> {
                try {
                    callback.accept(_get(dimIds));
                } catch (Throwable e) {
                    LOGGER.error("Failed to build client manifest", e);
                }
            });
        } catch (RejectedExecutionException e) {
            LOGGER.error("I/O pool rejected manifest scan task, pool shutting down?", e);
            callback.accept(Map.of());
        }
    }

    private static Map<RegionRef, Long> _get(Set<String> dimIds) {
        Map<RegionRef, Long> manifest = new HashMap<>();

        Path root = XaeroBridge.getCurrentServerDirectory();
        if (root == null || !Files.exists(root)) {
            LOGGER.info("Xaero map directory unavailable ({}), will request all regions from server", root);
            return manifest;
        }

        for (String dimId : dimIds) {
            String xaeroDim = XaeroBridge.getDimensionName(dimId);
            if (xaeroDim == null) {
                LOGGER.debug("No Xaero dimension name for {}, skipping", dimId);
                continue;
            }

            Path dimDir = root.resolve(xaeroDim);
            List<Path> zipFiles;
            try (Stream<Path> walk = Files.walk(dimDir)) {
                zipFiles = walk.filter(p -> p.toString().endsWith(".zip")).toList();
            } catch (IOException e) {
                LOGGER.debug("No Xaero local map directory for {} (resolved to {}), skipping", dimId, dimDir);
                continue;
            }

            for (Path zipPath : zipFiles) {
                try {
                    RegionRef ref = getRef(dimId, dimDir, zipPath);
                    long timestamp = Files.getLastModifiedTime(zipPath).toMillis();
                    manifest.put(ref, timestamp);
                } catch (IOException e) {
                    LOGGER.error("Failed to scan region file: {}, skipping", zipPath, e);
                    continue;
                }
            }
        }

        LOGGER.info("Built manifest with {} region(s)", manifest.size());

        return Map.copyOf(manifest);
    }

    private static RegionRef getRef(String dimId, Path dimDir, Path zipPath) {
        Path relative = dimDir.relativize(zipPath);
        int cave = PathUtils.getCaveByDir(relative);
        int[] coords = PathUtils.getCoordByZip(zipPath);
        return new RegionRef(dimId, cave, coords[0], coords[1]);
    }
}
