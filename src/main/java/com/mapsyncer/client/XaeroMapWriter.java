package com.mapsyncer.client;

import com.mapsyncer.network.RegionData;
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
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class XaeroMapWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapWriter.class);

    private static final Set<RegionCoord> loadedRegions = ConcurrentHashMap.newKeySet();

    public record RegionCoord(int x, int z, int caveLayer) {

        public boolean isSurfaceLayer() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }

    public record RegionWriteResult(Path mwDir, Path outputFile) {}

    public static RegionWriteResult writeChunkData(RegionData chunk) {
        String xaeroDim = XaeroWorldMapBridge.getDimensionName(chunk.ref.dimId());
        if (xaeroDim == null) {
            LOGGER.error(
                    "Unable to resolve Xaero dimension name for {}, skipping region ({}, {})",
                    chunk.ref.dimId(),
                    chunk.ref.regionX(),
                    chunk.ref.regionZ());
            return null;
        }

        Path serverDir = XaeroWorldMapBridge.getCurrentServerDirectory();
        if (serverDir == null) {
            LOGGER.error(
                    "Unable to resolve server directory, skipping region ({}, {}) dim={}",
                    chunk.ref.regionX(),
                    chunk.ref.regionZ(),
                    chunk.ref.dimId());
            return null;
        }

        String worldId = XaeroWorldMapBridge.getCurrentWorldId();
        if (worldId == null || worldId.isEmpty()) {
            LOGGER.error(
                    "Unable to resolve current world id from Xaero, skipping region ({}, {}) dim={}",
                    chunk.ref.regionX(),
                    chunk.ref.regionZ(),
                    chunk.ref.dimId());
            return null;
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

        triggerRegionLoad(chunk);

        return new RegionWriteResult(mwDir, outputFile);
    }

    public static void triggerRegionLoad(RegionData chunk) {
        boolean syncingCaveDimension = "minecraft:the_nether".equals(chunk.ref.dimId())
                || "the_nether".equals(chunk.ref.dimId())
                || "DIM-1".equals(chunk.ref.dimId());
        boolean shouldProcess = syncingCaveDimension ? !chunk.isSurfaceLayer() : chunk.isSurfaceLayer();
        if (!shouldProcess) {
            return;
        }

        RegionCoord coord = new RegionCoord(chunk.ref.regionX(), chunk.ref.regionZ(), chunk.ref.caveLayer());
        Minecraft.getInstance().execute(() -> loadRegion(coord));
    }

    private static void loadRegion(RegionCoord coord) {
        try {
            if (loadedRegions.contains(coord)) {
                LOGGER.debug(
                        "Region ({}, {}) layer={} already loaded, skipping", coord.x(), coord.z(), coord.caveLayer());
                return;
            }

            Object mapRegion = XaeroWorldMapBridge.getLeafMapRegion(coord.caveLayer(), coord.x(), coord.z(), true);
            if (mapRegion == null) {
                LOGGER.warn("Cannot create MapRegion ({}, {}) layer={}", coord.x(), coord.z(), coord.caveLayer());
                return;
            }

            if (!XaeroWorldMapBridge.prepareRegionLoad(mapRegion)) {
                LOGGER.warn(
                        "Region ({}, {}) layer={} load preparation failed", coord.x(), coord.z(), coord.caveLayer());
                return;
            }

            if (!XaeroWorldMapBridge.setLoadState(mapRegion, XaeroWorldMapBridge.LOAD_STATE_CLEARED)) {
                LOGGER.warn("Region ({}, {}) layer={} setLoadState failed", coord.x(), coord.z(), coord.caveLayer());
                return;
            }

            if (!XaeroWorldMapBridge.requestLoad(mapRegion, "sync", false)) {
                LOGGER.warn("Region ({}, {}) layer={} requestLoad failed", coord.x(), coord.z(), coord.caveLayer());
                return;
            }

            loadedRegions.add(coord);
        } catch (Exception e) {
            LOGGER.error(
                    "Failed to load region ({}, {}) layer={}: {}",
                    coord.x(),
                    coord.z(),
                    coord.caveLayer(),
                    e.getMessage(),
                    e);
        }
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
