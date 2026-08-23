package com.mapsyncer.client;

import com.mapsyncer.util.RegionMeta;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientTimestampCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientTimestampCache.class);

    private static final String CACHE_FILE_NAME = "sync_timestamps.cache";

    private static volatile @Nullable ClientTimestampCache instance;

    private static volatile @Nullable Path lastBaseDir = null;

    private final Path cacheFile;

    private final Map<String, RegionMeta> cache = new ConcurrentHashMap<>();

    private ClientTimestampCache(Path baseDir) {
        this.cacheFile = baseDir.resolve(CACHE_FILE_NAME);
        load();
    }

    public static @Nullable ClientTimestampCache getInstance(Path baseDir) {
        if (baseDir == null) {
            return instance;
        }

        ClientTimestampCache current = instance;
        if (current == null || lastBaseDir == null || !lastBaseDir.equals(baseDir)) {
            synchronized (ClientTimestampCache.class) {
                current = instance;
                if (current == null || lastBaseDir == null || !lastBaseDir.equals(baseDir)) {
                    current = new ClientTimestampCache(baseDir);
                    instance = current;
                    lastBaseDir = baseDir;
                    LOGGER.info("ClientTimestampCache initialized for baseDir: {}", baseDir);
                }
            }
        }
        return current;
    }

    public static void resetInstance() {
        if (instance != null) {
            instance.cache.clear();
            instance = null;
            lastBaseDir = null;
            LOGGER.info("ClientTimestampCache instance reset");
        }
    }

    private void load() {
        if (!Files.exists(cacheFile)) {
            LOGGER.info("Cache file not found, never synced before");
            return;
        }

        try {
            Properties props = new Properties();
            try (var in = Files.newInputStream(cacheFile)) {
                props.load(in);
            }

            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith("_")) {
                    RegionMeta entry = com.mapsyncer.util.RegionCacheIO.parseTimestamp(props.getProperty(key));
                    if (entry != null) {
                        cache.put(key, entry);
                    }
                }
            }

            LOGGER.info("Loaded cache: regions={}, file={}", cache.size(), cacheFile.getFileName());
        } catch (IOException e) {
            LOGGER.warn("Failed to load cache file: {}", e.getMessage());
        }
    }

    public void save() {
        try {
            Files.createDirectories(cacheFile.getParent());

            Properties props = new Properties();
            for (Map.Entry<String, RegionMeta> entry : cache.entrySet()) {
                props.setProperty(entry.getKey(), entry.getValue().format());
            }

            try (var out = Files.newOutputStream(cacheFile)) {

                StringBuilder content = new StringBuilder();
                content.append("# Sync timestamps cache\n");
                content.append("# Format: dimension/region_x_z = timestamp_seconds\n");

                for (Map.Entry<String, RegionMeta> entry : cache.entrySet()) {
                    content.append(entry.getKey())
                            .append("=")
                            .append(entry.getValue().format())
                            .append("\n");
                }

                out.write(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            LOGGER.debug("Saved cache: regions={}", cache.size());
        } catch (IOException e) {
            LOGGER.warn("Failed to save cache file: {}", e.getMessage());
        }
    }

    public void update(String relativePath, long timestampMillis) {
        cache.put(relativePath, new RegionMeta(timestampMillis));
    }

    public void remove(String relativePath) {
        if (relativePath != null) {
            cache.remove(relativePath);
        }
    }

    public Map<String, RegionMeta> getAll() {
        return Collections.unmodifiableMap(cache);
    }
}
