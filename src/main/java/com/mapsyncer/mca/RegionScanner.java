package com.mapsyncer.mca;

import com.mapsyncer.util.PathUtils;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegionScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionScanner.class);

    private static final Pattern REGION_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mc[ar]$");

    public record RegionCoords(int x, int z) {}

    public record RegionEntry(
            RegionCoords coords, long lastModifiedMillis, String dimId, Path regionDir, WorldBounds bounds) {}

    public record WorldBounds(int minY, int height, int logicalHeight, boolean hasCeiling, boolean hasSkylight) {

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

    public static List<RegionEntry> scan(MinecraftServer server) {
        List<RegionEntry> result = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            result.addAll(scan(level));
        }
        return List.copyOf(result);
    }

    private static List<RegionEntry> scan(ServerLevel level) {
        String dimId = PathUtils.getDimId(level);
        Path regionDir = getRegionDir(level);
        WorldBounds bounds = new WorldBounds(
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

    private static List<RegionEntry> scanRegionFiles(Path regionDir, String dimId, WorldBounds bounds) {
        if (regionDir == null || !Files.exists(regionDir)) {
            return List.of();
        }

        List<RegionEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                Matcher matcher = REGION_PATTERN.matcher(fileName);
                if (!matcher.matches()) {
                    continue;
                }
                try {
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    int regionX = Integer.parseInt(matcher.group(1));
                    int regionZ = Integer.parseInt(matcher.group(2));
                    entries.add(new RegionEntry(
                            new RegionCoords(regionX, regionZ),
                            attrs.lastModifiedTime().toMillis(),
                            dimId,
                            regionDir,
                            bounds));
                } catch (IOException e) {
                    LOGGER.warn("Failed to read attributes for {}", fileName, e);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan region directory: {}", regionDir, e);
        }
        return List.copyOf(entries);
    }
}
