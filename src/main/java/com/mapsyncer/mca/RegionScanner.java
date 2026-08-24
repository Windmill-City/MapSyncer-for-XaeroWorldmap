package com.mapsyncer.mca;

import com.mapsyncer.util.PathUtils;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegionScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionScanner.class);

    private static final Pattern REGION_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mc[ar]$");

    public record Region(int regionX, int regionZ, String dimId, Path regionDir, Bounds bounds) {}

    public record Bounds(int minY, int height, int logicalHeight, boolean hasCeiling, boolean hasSkylight) {

        public int maxY() {
            return minY + height;
        }

        public int logicalTopY() {
            return minY + logicalHeight - 1;
        }

        public boolean hasUpperZone() {
            return hasCeiling && logicalHeight < height;
        }
    }

    public static List<Region> scan(ServerLevel level) {
        String dimId = PathUtils.getDimId(level);
        Path regionDir = getRegionDir(level);
        Bounds bounds = new Bounds(
                level.getMinBuildHeight(),
                level.getHeight(),
                level.dimensionType().logicalHeight(),
                level.dimensionType().hasCeiling(),
                level.dimensionType().hasSkyLight());
        return scanRegionFiles(regionDir, dimId, bounds);
    }

    private static Path getRegionDir(ServerLevel level) {
        String dimId = PathUtils.getDimId(level);
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        Path path = null;
        switch (dimId) {
            case "minecraft:overworld" -> {
                path = root.resolve("region");
            }
            case "minecraft:the_nether" -> {
                path = root.resolve("DIM-1").resolve("region");
            }
            case "minecraft:the_end" -> {
                path = root.resolve("DIM1").resolve("region");
            }
            default -> {
                path = root.resolve("dimensions")
                        .resolve(dimId.replace(':', '/'))
                        .resolve("region");
            }
        }
        try {
            return path.toRealPath();
        } catch (IOException e) {
            LOGGER.warn("Failed to resolve region directory for dimension {} (resolved to {})", dimId, path, e);
            return null;
        }
    }

    private static List<Region> scanRegionFiles(Path regionDir, String dimId, Bounds bounds) {
        if (regionDir == null || !Files.exists(regionDir)) {
            return List.of();
        }

        List<Region> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                Matcher matcher = REGION_PATTERN.matcher(fileName);
                if (!matcher.matches()) {
                    continue;
                }
                try {
                    int regionX = Integer.parseInt(matcher.group(1));
                    int regionZ = Integer.parseInt(matcher.group(2));
                    entries.add(new Region(regionX, regionZ, dimId, regionDir, bounds));
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid region coordinates for {}", fileName, e);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan region directory: {}", regionDir, e);
        }
        return List.copyOf(entries);
    }
}
