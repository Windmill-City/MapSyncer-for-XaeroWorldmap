package com.mapsyncer.server;

import com.mapsyncer.network.RegionRef;
import com.mapsyncer.util.PathUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ManifestServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManifestServer.class);

    private static volatile Map<RegionRef, Long> manifest = Map.of();

    private static volatile boolean isValid = false;

    public static Map<RegionRef, Long> get(MinecraftServer server) {
        if (!isValid) {
            synchronized (ManifestServer.class) {
                if (!isValid) {
                    _get(server);
                }
            }
        }
        return Collections.unmodifiableMap(manifest);
    }

    private static void _get(MinecraftServer server) {
        Map<RegionRef, Long> rebuilt = new HashMap<>();

        for (ServerLevel level : server.getAllLevels()) {
            String dimId = PathUtils.getDimId(level);
            Path dimDir = PathUtils.getDimDirServer(dimId);
            if (!Files.isDirectory(dimDir)) {
                continue;
            }

            try (Stream<Path> stream = Files.walk(dimDir)) {
                for (Path zipPath :
                        (Iterable<Path>) stream.filter(p -> p.toString().endsWith(".zip"))::iterator) {
                    try {
                        Path relative = dimDir.relativize(zipPath);
                        int cave = PathUtils.getCaveByDir(relative);
                        int[] coords = PathUtils.getCoordByZip(zipPath);
                        RegionRef ref = new RegionRef(dimId, cave, coords[0], coords[1]);
                        long timestamp = readMtimeMillis(zipPath);
                        rebuilt.put(ref, timestamp);
                    } catch (Throwable e) {
                        LOGGER.warn(
                                "Skipping malformed region file {} while building manifest for {}", zipPath, dimId, e);
                    }
                }
            } catch (Throwable e) {
                LOGGER.error("Failed to walk cache directory while building manifest for {}", dimId, e);
            }
        }

        manifest = rebuilt;
        isValid = true;
        LOGGER.info("Manifest cache built with {} entries", rebuilt.size());
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

    public static void invalidate() {
        isValid = false;
        manifest = Map.of();
        LOGGER.debug("ManifestCache invalidated");
    }
}
