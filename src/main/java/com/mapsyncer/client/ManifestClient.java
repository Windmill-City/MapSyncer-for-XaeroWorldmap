package com.mapsyncer.client;

import com.mapsyncer.network.payload.RegionRef;
import com.mapsyncer.util.PathMapping;
import com.mapsyncer.util.RegionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ManifestClient {

    public record MetaScanResult(
            Map<RegionRef, Long> meta, boolean success, int failedFiles, @Nullable String failureReason) {

        public static MetaScanResult ok(Map<RegionRef, Long> meta) {
            return new MetaScanResult(meta != null ? meta : Collections.emptyMap(), true, 0, null);
        }

        public static MetaScanResult failure(String reason, int failedFiles) {
            return new MetaScanResult(Collections.emptyMap(), false, failedFiles, reason);
        }

        public boolean isSuccess() {
            return success;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ManifestClient.class);

    private static volatile @Nullable ExecutorService executor;

    private static final AtomicInteger poolUsers = new AtomicInteger(0);

    private static ExecutorService getExecutor() {
        ExecutorService exec = executor;
        if (exec == null || exec.isShutdown()) {
            synchronized (ManifestClient.class) {
                exec = executor;
                if (exec == null || exec.isShutdown()) {
                    exec = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "mapsyncer-scan");
                        t.setDaemon(true);
                        return t;
                    });
                    executor = exec;
                }
            }
        }
        return exec;
    }

    public static void computeMetaForSyncAsync(Path mapDir, Set<String> dimIds, Consumer<MetaScanResult> onComplete) {
        poolUsers.incrementAndGet();
        getExecutor().submit(() -> {
            try {
                onComplete.accept(computeMetaForSyncWorker(mapDir, dimIds));
            } catch (Exception e) {
                LOGGER.error("Failed to scan map asynchronously", e);
                onComplete.accept(MetaScanResult.failure("async_error", 0));
            } finally {
                poolUsers.decrementAndGet();
            }
        });
    }

    private static MetaScanResult computeMetaForSyncWorker(Path mapDir, Set<String> dimIds) {
        Map<RegionRef, Long> metaMap = new HashMap<>();

        if (mapDir == null || !Files.exists(mapDir)) {
            LOGGER.info("Map directory does not exist or is null, will request all regions from server");
            return MetaScanResult.ok(metaMap);
        }

        Path serverDir = findServerDir(mapDir);
        if (serverDir == null) {
            LOGGER.error("Could not resolve Multiplayer server directory from {}", mapDir);
            return MetaScanResult.failure("server_dir", 0);
        }

        int failedFiles = 0;
        int totalFiles = 0;

        for (String dimId : dimIds) {
            String xaeroDim = XaeroReflectionHelper.getDimensionName(dimId);
            if (xaeroDim == null) {
                xaeroDim = PathMapping.toXaeroDimension(dimId);
            }
            Path dimDir = serverDir.resolve(xaeroDim);
            if (!Files.isDirectory(dimDir)) {
                LOGGER.debug("No local map directory for dimension {} ({}), skipping", dimId, xaeroDim);
                continue;
            }

            java.util.List<Path> zipFiles;
            try (Stream<Path> walk = Files.walk(dimDir)) {
                zipFiles = walk.filter(p -> p.toString().endsWith(".zip")).toList();
            } catch (IOException e) {
                LOGGER.error("Failed to walk map directory {}", dimDir, e);
                return MetaScanResult.failure("walk_error", 0);
            }

            totalFiles += zipFiles.size();

            for (Path zipPath : zipFiles) {
                try {
                    RegionRef ref = buildKey(dimId, dimDir, zipPath);
                    long timestampMillis = getFileModificationTime(zipPath);
                    LOGGER.debug("Region {}: ts={}ms (mtime)", ref, timestampMillis);
                    metaMap.put(ref, timestampMillis);
                } catch (Exception e) {
                    failedFiles++;
                    LOGGER.warn("Failed to scan region file: {}", zipPath, e);
                }
            }
        }

        if (failedFiles > 0) {
            LOGGER.error("Scan completed with {} failed file(s) out of {}", failedFiles, totalFiles);
            return MetaScanResult.failure("partial_error", failedFiles);
        }

        LOGGER.info("Found {} regions with metadata", metaMap.size());

        return MetaScanResult.ok(metaMap);
    }

    private static @Nullable Path findServerDir(Path mapDir) {
        Path current = mapDir;

        while (current != null) {
            String name = current.getFileName() != null ? current.getFileName().toString() : "";
            if (name.startsWith("Multiplayer_")) {
                return current;
            }
            current = current.getParent();
        }

        return null;
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

    public static void shutdown() {
        synchronized (ManifestClient.class) {
            if (poolUsers.get() > 0) {
                LOGGER.debug("Deferring scan executor shutdown, {} active scans", poolUsers.get());
                return;
            }
            ExecutorService exec = executor;
            if (exec != null && !exec.isShutdown()) {
                exec.shutdown();
                executor = null;
                LOGGER.debug("ClientMetaScanner scan executor shut down");
            }
        }
    }
}
