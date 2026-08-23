package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.util.RegionMeta;
import com.mapsyncer.util.RegionCacheIO;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenerationCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationCache.class);

    private static final int MAX_CACHE_REGIONS = ModConfig.MAX_REGION_META_CACHE;

    private static volatile @Nullable GenerationCache instance;

    private final Path cacheFile;

    private final Map<String, RegionMeta> cache = new ConcurrentHashMap<>();

    private GenerationCache(Path cacheDir) {
        this.cacheFile = cacheDir.resolve("generation_cache.properties");

        load();
    }

    public static GenerationCache getInstance(Path cacheDir) {
        GenerationCache current = instance;
        if (current == null) {
            synchronized (GenerationCache.class) {
                current = instance;
                if (current == null) {
                    current = new GenerationCache(cacheDir);
                    instance = current;
                }
            }
        }
        return current;
    }

    private void load() {
        Map<String, RegionMeta> loaded = RegionCacheIO.load(cacheFile, RegionCacheIO::parseTimestampHash);
        cache.putAll(loaded);
    }

    public void save() {
        RegionCacheIO.save(
                cacheFile,
                new HashMap<>(cache),
                RegionMeta::format,
                "Generation cache for map regions\nFormat: dimension/region_x_z = timestamp_seconds:hash\nHash is CRC32 of file content");
    }

    public void update(String relativePath, long timestampSeconds, String hash) {
        cache.put(relativePath, new RegionMeta(timestampSeconds, hash));
        trimIfOverLimit();
    }

    private void trimIfOverLimit() {
        if (cache.size() <= MAX_CACHE_REGIONS) {
            return;
        }

        int toRemove = cache.size() - MAX_CACHE_REGIONS;
        LOGGER.info(
                "Cache size {} exceeds limit {}, trimming {} oldest entries",
                cache.size(),
                MAX_CACHE_REGIONS,
                toRemove);

        cache.entrySet().stream()
                .sorted((a, b) -> Long.compare(
                        a.getValue().timestampSeconds(), b.getValue().timestampSeconds()))
                .limit(toRemove)
                .map(Map.Entry::getKey)
                .forEach(cache::remove);

        LOGGER.info("Cache trimmed to {} entries", cache.size());
    }

    public @Nullable RegionMeta getMeta(String relativePath) {
        return cache.get(relativePath);
    }

    public void remove(String relativePath) {
        if (relativePath != null) {
            cache.remove(relativePath);
        }
    }

    public long getLastGenerationTime() {
        return cache.values().stream()
                .mapToLong(RegionMeta::timestampSeconds)
                .max()
                .orElse(0);
    }

    public int removeByPrefix(String prefix) {
        int removed = 0;
        for (String key : List.copyOf(cache.keySet())) {
            if (key.startsWith(prefix)) {
                cache.remove(key);
                removed++;
            }
        }
        if (removed > 0) {
            save();
        }
        return removed;
    }
}
