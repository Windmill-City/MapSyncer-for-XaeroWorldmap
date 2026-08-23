package com.mapsyncer.client;

import com.mapsyncer.util.PathMapping;
import com.mapsyncer.util.RegionMeta;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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
            Map<String, RegionMeta> meta, boolean success, int failedFiles, @Nullable String failureReason) {

        public static MetaScanResult ok(Map<String, RegionMeta> meta) {
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

    public static void computeMetaForSyncAsync(Path mapDir, Consumer<MetaScanResult> onComplete) {
        poolUsers.incrementAndGet();
        getExecutor().submit(() -> {
            try {
                onComplete.accept(computeMetaForSyncWorker(mapDir));
            } catch (Exception e) {
                LOGGER.error("Failed to scan map asynchronously", e);
                onComplete.accept(MetaScanResult.failure("async_error", 0));
            } finally {
                poolUsers.decrementAndGet();
            }
        });
    }

    private static MetaScanResult computeMetaForSyncWorker(Path mapDir) {
        Map<String, RegionMeta> metaMap = new HashMap<>();

        if (mapDir == null || !Files.exists(mapDir)) {
            LOGGER.info("Map directory does not exist or is null, will request all regions from server");
            return MetaScanResult.ok(metaMap);
        }

        Path serverDir = findServerDir(mapDir);
        if (serverDir == null) {
            LOGGER.error("Could not resolve Multiplayer server directory from {}", mapDir);
            return MetaScanResult.failure("server_dir", 0);
        }

        java.util.List<Path> zipFiles;
        try (Stream<Path> walk = Files.walk(mapDir)) {
            zipFiles = walk.filter(p -> p.toString().endsWith(".zip")).toList();
        } catch (IOException e) {
            LOGGER.error("Failed to walk map directory {}", mapDir, e);
            return MetaScanResult.failure("walk_error", 0);
        }

        LOGGER.info("Scanning timestamps for {} region files in {}", zipFiles.size(), mapDir);

        int failedFiles = 0;
        int totalFiles = zipFiles.size();

        for (Path zipPath : zipFiles) {
            try {
                String fileName = zipPath.getFileName().toString();
                if (!fileName.endsWith(".zip")) continue;

                String relativePath = build(zipPath, serverDir);
                long timestampMillis = getFileModificationTime(zipPath);
                LOGGER.debug("Region {}: ts={}ms (mtime)", relativePath, timestampMillis);

                metaMap.put(relativePath, new RegionMeta(timestampMillis));
            } catch (Exception e) {
                failedFiles++;
                LOGGER.warn("Failed to scan region file: {}", zipPath, e);
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

    static String build(Path zipPath, Path serverDir) {

        String relative = serverDir.relativize(zipPath).toString();
        relative = relative.replace("\\", "/");

        if (relative.endsWith(".zip")) {
            relative = relative.substring(0, relative.length() - 4);
        }

        String[] parts = relative.split("/");
        if (parts.length < 3) {
            LOGGER.warn("Unexpected path format: {}", relative);
            return relative;
        }

        String dirName = parts[0];
        String regionCoords = parts[parts.length - 1];

        int caveLayer = Integer.MAX_VALUE;
        boolean hasCaves = false;
        for (int i = 1; i < parts.length - 2; i++) {
            if (parts[i].equals("caves") && i + 1 < parts.length - 1) {
                hasCaves = true;
                try {
                    caveLayer = Integer.parseInt(parts[i + 1]);
                    LOGGER.debug("Found caves layer {} at index {} in path: {}", caveLayer, i, relative);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid cave layer at index {} in path: {}", i + 1, relative);
                }
                break;
            }
        }

        if (hasCaves) {
            LOGGER.debug("Path has caves layer: {}", relative);
        }

        String xaeroDim = ensureCorrectXaeroFormat(dirName);

        String serverPath;
        if (caveLayer == Integer.MAX_VALUE) {

            serverPath = xaeroDim + "/" + regionCoords;
        } else {

            serverPath = xaeroDim + "/caves/" + caveLayer + "/" + regionCoords;
        }

        LOGGER.debug("buildRelativePath: {} -> {} (dirName={}, xaeroDim={})", relative, serverPath, dirName, xaeroDim);
        return serverPath;
    }

    private static String ensureCorrectXaeroFormat(String dirName) {

        if (dirName.equals("null") || dirName.equals("DIM-1") || dirName.equals("DIM1")) {
            return dirName;
        }

        if (dirName.contains("$")) {
            return dirName;
        }

        if (dirName.startsWith("DIM") || dirName.startsWith("DIM-")) {
            return dirName;
        }

        String converted = PathMapping.toXaeroDimension(dirName);
        if (!converted.equals(dirName)) {
            LOGGER.info("Converted xaeroDim via mapping: {} -> {}", dirName, converted);
            return converted;
        }

        LOGGER.warn("Could not convert dirName '{}' to correct Xaero format, sync may fail", dirName);
        return dirName;
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
