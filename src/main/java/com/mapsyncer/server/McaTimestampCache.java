package com.mapsyncer.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class McaTimestampCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(McaTimestampCache.class);
    private static final String CACHE_FILE_NAME = "mca_timestamps.cache";

    private final Map<String, Map<String, Long>> timestampCache = new ConcurrentHashMap<>();

    private static final int MAX_CACHE_REGIONS = 50000;

    private final Path cacheFilePath;

    private static volatile McaTimestampCache instance;

    public static McaTimestampCache getInstance(Path baseDir) {
        if (instance == null) {
            synchronized (McaTimestampCache.class) {
                if (instance == null) {
                    instance = new McaTimestampCache(baseDir);
                }
            }
        }
        return instance;
    }

    private McaTimestampCache(Path baseDir) {
        this.cacheFilePath = baseDir.resolve(CACHE_FILE_NAME);

        loadCache();
    }

    private void loadCache() {
        if (!Files.exists(cacheFilePath)) {
            LOGGER.info("No existing timestamp cache found, will create new one");
            return;
        }

        try (InputStream is = Files.newInputStream(cacheFilePath)) {
            Properties props = new Properties();
            props.load(is);

            for (String key : props.stringPropertyNames()) {
                try {

                    long timestampSeconds = Long.parseLong(props.getProperty(key));
                    long timestampMillis = timestampSeconds * 1000;

                    String[] parts = key.split("/");
                    if (parts.length == 2) {
                        String dimension = parts[0];
                        String regionKey = parts[1];
                        timestampCache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                                     .put(regionKey, timestampMillis);
                    }
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid timestamp for {}: {}", key, props.getProperty(key));
                }
            }

            int totalRegions = timestampCache.values().stream().mapToInt(Map::size).sum();
            LOGGER.info("Loaded timestamp cache: {} dimensions, {} regions",
                timestampCache.size(), totalRegions);
        } catch (IOException e) {
            LOGGER.warn("Failed to load timestamp cache, will rebuild: {}", e.getMessage());
            timestampCache.clear();
        }
    }

    public void saveCache() {
        try {
            Files.createDirectories(cacheFilePath.getParent());

            Properties props = new Properties();
            for (Map.Entry<String, Map<String, Long>> dimEntry : timestampCache.entrySet()) {
                String dimension = dimEntry.getKey();
                for (Map.Entry<String, Long> regionEntry : dimEntry.getValue().entrySet()) {

                    String key = dimension + "/" + regionEntry.getKey();

                    long timestampSeconds = regionEntry.getValue() / 1000;
                    props.setProperty(key, String.valueOf(timestampSeconds));
                }
            }

            Path tempFile = cacheFilePath.resolveSibling(CACHE_FILE_NAME + ".temp");
            try (OutputStream os = Files.newOutputStream(tempFile)) {
                props.store(os, "MCA file modification timestamps (seconds since epoch)\nFormat: dimension/region_x_z = timestamp");
            }
            Files.move(tempFile, cacheFilePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            int totalRegions = timestampCache.values().stream().mapToInt(Map::size).sum();
            LOGGER.info("Saved timestamp cache: {} dimensions, {} regions to {}",
                timestampCache.size(), totalRegions, cacheFilePath);
        } catch (IOException e) {
            LOGGER.error("Failed to save timestamp cache", e);
        }
    }

    public long getFileTimestamp(Path mcaPath) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(mcaPath, BasicFileAttributes.class);
            FileTime lastModified = attrs.lastModifiedTime();
            return lastModified.toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    public void updateTimestamp(String dimension, int regionX, int regionZ, Path mcaPath) {
        long timestamp = getFileTimestamp(mcaPath);
        if (timestamp < 0) {
            LOGGER.warn("Could not get timestamp for {}", mcaPath);
            return;
        }

        String regionKey = regionX + "_" + regionZ;
        timestampCache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                      .put(regionKey, timestamp);

        int totalRegions = getTotalCachedRegions();
        if (totalRegions > MAX_CACHE_REGIONS) {
            LOGGER.warn("Timestamp cache size {} exceeds limit {}, consider calling trimStaleEntries()",
                totalRegions, MAX_CACHE_REGIONS);
        }

        LOGGER.debug("Updated timestamp cache for {} / {}: {}", dimension, regionKey, timestamp);
    }

    private int getTotalCachedRegions() {
        return timestampCache.values().stream().mapToInt(Map::size).sum();
    }

    public void trimStaleEntries(String dimension, Path regionDir) {
        Map<String, Long> dimCache = timestampCache.get(dimension);
        if (dimCache == null || dimCache.isEmpty()) return;

        int before = dimCache.size();
        java.util.List<String> toRemove = new java.util.ArrayList<>();

        for (String regionKey : dimCache.keySet()) {
            String[] parts = regionKey.split("_");
            if (parts.length == 2) {
                try {
                    int regionX = Integer.parseInt(parts[0]);
                    int regionZ = Integer.parseInt(parts[1]);
                    Path mcaPath = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
                    if (!Files.exists(mcaPath)) {
                        toRemove.add(regionKey);
                    }
                } catch (NumberFormatException ignored) {

                    toRemove.add(regionKey);
                }
            }
        }

        for (String key : toRemove) {
            dimCache.remove(key);
        }

        if (!toRemove.isEmpty()) {
            LOGGER.info("Trimmed {} stale timestamp entries for dimension {} (before: {}, after: {})",
                toRemove.size(), dimension, before, dimCache.size());
        }
    }

    public java.util.List<RegionScanner.RegionCoords> classifyUpdates(
            String dimension, java.util.List<RegionScanner.RegionFileEntry> fileEntries) {
        java.util.List<RegionScanner.RegionCoords> needsRegeneration = new java.util.ArrayList<>();
        Map<String, Long> dimCache = timestampCache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());

        for (RegionScanner.RegionFileEntry entry : fileEntries) {
            RegionScanner.RegionCoords coords = entry.coords();
            String regionKey = coords.x() + "_" + coords.z();
            long currentTimestamp = entry.lastModifiedMillis();
            Long cachedTimestamp = dimCache.get(regionKey);

            long currentSeconds = currentTimestamp / 1000;
            long cachedSeconds = cachedTimestamp != null ? cachedTimestamp / 1000 : 0;

            if (cachedTimestamp == null || currentSeconds > cachedSeconds) {
                needsRegeneration.add(coords);
                dimCache.put(regionKey, currentTimestamp);
                if (cachedTimestamp != null) {
                    LOGGER.info("Detected update in {} / {}: cached={}s, current={}s",
                            dimension, regionKey, cachedSeconds, currentSeconds);
                }
            }
        }

        if (getTotalCachedRegions() > MAX_CACHE_REGIONS) {
            Path regionDir = fileEntries.isEmpty() ? null : fileEntries.get(0).path().getParent();
            if (regionDir != null) {
                trimStaleEntries(dimension, regionDir);
            }
        }

        return needsRegeneration;
    }

    public java.util.List<RegionScanner.RegionCoords> scanAndUpdate(String dimension, Path regionDir) {
        if (!Files.exists(regionDir)) {
            LOGGER.warn("Region directory not found: {}", regionDir);
            return new java.util.ArrayList<>();
        }
        return classifyUpdates(dimension, RegionScanner.listRegionFiles(regionDir));
    }
}