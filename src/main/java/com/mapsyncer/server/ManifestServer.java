package com.mapsyncer.server;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.RegionRef;
import com.mapsyncer.util.ApiHelper;
import com.mapsyncer.util.PathMapping;
import com.mapsyncer.util.RegionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ManifestServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManifestServer.class);

    private static final ManifestServer INSTANCE = new ManifestServer();

    private volatile Map<RegionRef, Long> manifest = Map.of();

    private volatile boolean isValid = false;

    public static ManifestServer get() {
        return INSTANCE;
    }

    public Map<RegionRef, Long> build(MinecraftServer server) {
        if (!isValid) {
            synchronized (this) {
                if (!isValid) {
                    _build(server);
                }
            }
        }
        return manifest;
    }

    private void _build(MinecraftServer server) {
        Map<RegionRef, Long> rebuilt = new HashMap<>();

        for (ServerLevel level : server.getAllLevels()) {
            String dimId = ApiHelper.getDimId(level.dimension());
            String dimFolderName = PathMapping.toServerFolderName(dimId);
            Path dimDir = MapSyncer.CACHE_DIR.resolve(dimFolderName);
            if (!Files.isDirectory(dimDir)) {
                continue;
            }

            try (Stream<Path> stream = Files.walk(dimDir)) {
                stream.filter(p -> p.toString().endsWith(".zip")).forEach(zipPath -> {
                    String relative = dimDir.relativize(zipPath).toString().replace("\\", "/");
                    int caveLayer = RegionKey.caveLayerFromRelative(relative);
                    int[] coords = RegionKey.coordsFromZipFileName(zipPath);
                    RegionRef ref = new RegionRef(dimId, caveLayer, coords[0], coords[1]);
                    long timestamp = readMtimeMillis(zipPath);
                    rebuilt.put(ref, timestamp);
                });
            } catch (IOException e) {
                LOGGER.error("Failed to walk cache directory while building manifest for {}", dimId, e);
            }
        }

        manifest = rebuilt;
        isValid = true;
        LOGGER.info("Manifest cache built for {} with {} entries", MapSyncer.CACHE_DIR, rebuilt.size());
    }

    private static long readMtimeMillis(Path zipPath) {
        try {
            FileTime mtime = Files.getLastModifiedTime(zipPath);
            return mtime.toMillis();
        } catch (IOException e) {
            LOGGER.warn("Failed to read mtime for {}, using current time", zipPath);
            return System.currentTimeMillis();
        }
    }

    public Path resolveZipPath(RegionRef ref) {
        Path baseOutputDir = MapSyncer.CACHE_DIR.resolve(PathMapping.toServerFolderName(ref.dimId()));
        Path outputDir = ModConfig.outputDir(baseOutputDir, ref.caveLayer());
        return outputDir.resolve(ref.regionX() + "_" + ref.regionZ() + ".zip");
    }

    public Long getTimestamp(RegionRef ref) {
        return manifest.get(ref);
    }

    public void invalidate() {
        isValid = false;
        manifest = Map.of();
        LOGGER.debug("ManifestCache invalidated");
    }
}
