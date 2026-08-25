package com.mapsyncer.server;

import com.mapsyncer.util.PathUtils;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class MapCacheManager {

    private static final Logger LOGGER = LogManager.getLogger(MapCacheManager.class);

    static List<MapCacheStats> getCacheStats() {
        List<MapCacheStats> stats = new ArrayList<>();

        if (!Files.exists(PathUtils.CACHE_DIR)) {
            return stats;
        }

        try (DirectoryStream<Path> dimDirs = Files.newDirectoryStream(PathUtils.CACHE_DIR)) {
            for (Path dimDir : dimDirs) {
                if (!Files.isDirectory(dimDir)) continue;

                String folder = dimDir.getFileName().toString();

                int regionCount = 0;
                long totalSize = 0;

                try (Stream<Path> files = Files.walk(dimDir)) {
                    List<Path> zipFiles =
                            files.filter(p -> p.toString().endsWith(".zip")).toList();

                    regionCount = zipFiles.size();
                    totalSize = zipFiles.stream()
                            .mapToLong(p -> {
                                try {
                                    return Files.size(p);
                                } catch (IOException e) {
                                    return 0;
                                }
                            })
                            .sum();
                }

                if (regionCount > 0) {
                    stats.add(new MapCacheStats(folder, regionCount, totalSize));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to get cache stats", e);
        }

        return stats;
    }

    static int purgeCache() {
        ManifestServer.invalidate();

        if (!Files.exists(PathUtils.CACHE_DIR)) {
            return 0;
        }

        int deleted = 0;
        try (Stream<Path> paths = Files.walk(PathUtils.CACHE_DIR)) {
            List<Path> sorted = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path p : sorted) {
                try {
                    if (!Files.isDirectory(p)) {
                        deleted++;
                    }
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete {}", p, e);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to purge cache", e);
        }

        return deleted;
    }

    record MapCacheStats(String folder, int regions, long size) {}
}
