package com.mapsyncer.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RegionCacheIO {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionCacheIO.class);

    public static Map<String, RegionMeta> load(Path cacheFile) {
        Map<String, RegionMeta> cache = new HashMap<>();

        if (cacheFile == null || !Files.exists(cacheFile)) {
            LOGGER.info("Cache file not found: {}", cacheFile);
            return cache;
        }

        try (InputStream is = Files.newInputStream(cacheFile)) {
            Properties props = new Properties();
            props.load(is);

            for (String key : props.stringPropertyNames()) {
                RegionMeta value = parseTimestamp(props.getProperty(key));
                if (value != null) {
                    cache.put(key, value);
                } else {
                    LOGGER.warn("Invalid cache entry for {}: {}", key, props.getProperty(key));
                }
            }

            LOGGER.info("Loaded {} entries from cache file: {}", cache.size(), cacheFile.getFileName());
        } catch (IOException e) {
            LOGGER.error("Failed to load cache file: {}", cacheFile, e);
        }

        return cache;
    }

    public static void save(Path cacheFile, Map<String, RegionMeta> cache, String header) {
        if (cacheFile == null) {
            LOGGER.warn("Cache file path is null, skip saving");
            return;
        }

        try {
            Files.createDirectories(cacheFile.getParent());

            Properties props = new Properties();
            for (Map.Entry<String, RegionMeta> entry : cache.entrySet()) {
                props.setProperty(entry.getKey(), entry.getValue().format());
            }

            try (OutputStream os = Files.newOutputStream(cacheFile)) {
                props.store(os, header != null ? header : "Cache file");
            }

            LOGGER.info("Saved {} entries to cache file: {}", cache.size(), cacheFile.getFileName());
        } catch (IOException e) {
            LOGGER.error("Failed to save cache file: {}", cacheFile, e);
        }
    }

    public static @Nullable RegionMeta parseTimestamp(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        try {
            return new RegionMeta(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
