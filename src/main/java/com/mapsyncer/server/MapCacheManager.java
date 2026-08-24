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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapCacheManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapCacheManager.class);

    public static List<MapCacheStats> getCacheStats() {
        List<MapCacheStats> stats = new ArrayList<>();

        if (!Files.exists(PathUtils.CACHE_DIR)) {
            return stats;
        }

        try (DirectoryStream<Path> dimDirs = Files.newDirectoryStream(PathUtils.CACHE_DIR)) {
            for (Path dimDir : dimDirs) {
                if (!Files.isDirectory(dimDir)) continue;

                String dimName = dimDir.getFileName().toString();
                String friendlyName = friendlyDimensionName(dimName);

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
                    stats.add(new MapCacheStats(friendlyName, regionCount, totalSize));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to get cache stats", e);
        }

        return stats;
    }

    public static int purgeCache() {
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

    public static String friendlyDimensionName(String dimPath) {
        if (dimPath == null || dimPath.isEmpty()) {
            return "minecraft:overworld";
        }
        if (dimPath.startsWith("minecraft:")) {
            return dimPath;
        }
        int dollarIndex = dimPath.indexOf('$');
        if (dollarIndex > 0) {
            String namespace = dimPath.substring(0, dollarIndex);
            String path = dimPath.substring(dollarIndex + 1).replace('%', '/').replace(',', '.');
            return namespace + ":" + path;
        }
        if (dimPath.contains(":")) {
            return dimPath;
        }
        return "minecraft:" + dimPath;
    }

    public record MapCacheStats(String dimension, int regionCount, long sizeBytes) {

        public double sizeMB() {
            return sizeBytes / (1024.0 * 1024.0);
        }
    }
}
