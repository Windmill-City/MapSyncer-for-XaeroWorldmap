package com.mapsyncer.client;

import com.mapsyncer.network.payload.RegionData;
import com.mapsyncer.util.PathMapping;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class XaeroMapDataHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapDataHandler.class);

    private static final Set<RegionCoord> updatedRegions = ConcurrentHashMap.newKeySet();

    private static final Set<RegionCoord> preUnloadedRegions = ConcurrentHashMap.newKeySet();

    public record RegionCoord(int x, int z, int caveLayer) {

        public boolean isSurfaceLayer() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }

    public static void clearRegionTracking() {
        updatedRegions.clear();
        preUnloadedRegions.clear();
        LOGGER.debug("Cleared region tracking sets");
    }

    public static void recordUpdatedRegionCoords(Set<RegionCoord> coords) {
        updatedRegions.clear();
        updatedRegions.addAll(coords);
        LOGGER.debug("Recorded {} updated region coords for selective reset", updatedRegions.size());
    }

    public record RegionWriteResult(Path mwDir, Path outputFile) {}

    public static @Nullable RegionWriteResult writeChunkData(RegionData chunk, Path serverDir, String worldId) {
        String xaeroDim = XaeroReflectionHelper.getDimensionName(chunk.ref.dimId());
        if (xaeroDim == null) {
            xaeroDim = PathMapping.toXaeroDimension(chunk.ref.dimId());
        }
        Path dimDir = serverDir.resolve(xaeroDim);
        Path mwDir = dimDir.resolve("mw$" + worldId);

        Path targetDir;
        if (chunk.ref.caveLayer() == Integer.MAX_VALUE) {
            targetDir = mwDir;
        } else {
            targetDir = mwDir.resolve("caves").resolve(String.valueOf(chunk.ref.caveLayer()));
        }

        Path outputFile = targetDir.resolve(chunk.ref.regionX() + "_" + chunk.ref.regionZ() + ".zip");
        Path tempFile = targetDir.resolve(chunk.ref.regionX() + "_" + chunk.ref.regionZ() + ".zip.temp");

        try {
            Files.createDirectories(targetDir);

            try (OutputStream fileOut = Files.newOutputStream(tempFile)) {
                fileOut.write(chunk.data);
            }
            Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug(
                    "Wrote map file: {} (layer={}, {} bytes)",
                    outputFile,
                    chunk.isSurfaceLayer() ? "surface" : chunk.ref.caveLayer(),
                    chunk.data.length);
        } catch (IOException e) {
            LOGGER.error("Failed to write map file: {}", outputFile, e);
            return null;
        }

        return new RegionWriteResult(mwDir, outputFile);
    }

    public static void clearRegionCacheFiles(Path mwDir, RegionCoord coord) {
        if (mwDir == null) {
            return;
        }

        String baseName = coord.x() + "_" + coord.z();
        for (Path cacheDir : findCacheDirectories(mwDir)) {
            Path cacheRoot = coord.isSurfaceLayer()
                    ? cacheDir
                    : cacheDir.resolve("caves").resolve(String.valueOf(coord.caveLayer()));
            deleteRegionCacheFile(cacheRoot.resolve(baseName + ".xwmc"));
            deleteRegionCacheFile(cacheRoot.resolve(baseName + ".xwmc.outdated"));
        }
    }

    private static void deleteRegionCacheFile(Path cacheFile) {
        if (!Files.exists(cacheFile)) {
            return;
        }
        try {
            Files.deleteIfExists(cacheFile);
            LOGGER.debug("Cleared region cache: {}", cacheFile);
        } catch (IOException e) {
            LOGGER.warn("Failed to clear region cache: {}", cacheFile, e);
        }
    }

    private static List<Path> findCacheDirectories(Path mwDir) {
        List<Path> cacheDirs = new ArrayList<>();

        try {
            Path cache = mwDir.resolve("cache");
            Path cache1 = mwDir.resolve("cache_1");

            if (Files.isDirectory(cache)) {
                cacheDirs.add(cache);
            }
            if (Files.isDirectory(cache1)) {
                cacheDirs.add(cache1);
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(mwDir, "cache_*")) {
                for (Path dir : stream) {
                    if (Files.isDirectory(dir) && !cacheDirs.contains(dir)) {
                        cacheDirs.add(dir);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to find cache directories under {}", mwDir, e);
        }

        return cacheDirs;
    }
}
