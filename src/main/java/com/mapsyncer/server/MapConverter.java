package com.mapsyncer.server;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.config.DimensionScanConfig;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.RegionGenerationPlanner;
import com.mapsyncer.mca.DimensionInfo;
import com.mapsyncer.mca.RegionConverter;
import com.mapsyncer.mca.RegionConverter.ConvertedRegion;
import com.mapsyncer.mca.RegionConverter.LayerConvertedRegion;
import com.mapsyncer.mca.convert.scan.RegionScanPass;
import com.mapsyncer.server.RegionScanner.DimensionRegions;
import com.mapsyncer.server.RegionScanner.RegionCoords;
import com.mapsyncer.util.ApiHelper;
import com.mapsyncer.util.PathMapping;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapConverter.class);

    private static final AtomicBoolean isRunning = new AtomicBoolean(false);

    private static final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private static final AtomicInteger processedCountAtomic = new AtomicInteger(0);

    private static final AtomicInteger skippedCount = new AtomicInteger(0);

    private static final AtomicInteger convertedCountAtomic = new AtomicInteger(0);

    private static final AtomicInteger skippedEmptyContentCount = new AtomicInteger(0);

    private static volatile int totalCount = 0;

    private static final List<String> completedDimensions = new CopyOnWriteArrayList<>();

    public static void cleanupCacheDir() {
        XaeroWriter.cleanStaleFiles(MapSyncer.CACHE_DIR);
    }

    public static boolean generateAll(MinecraftServer server) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.warn("Conversion already in progress, rejecting generateAll");
            return false;
        }
        cancelRequested.set(false);
        processedCountAtomic.set(0);
        skippedCount.set(0);
        convertedCountAtomic.set(0);
        skippedEmptyContentCount.set(0);
        completedDimensions.clear();

        List<DimensionRegions> allRegions = RegionScanner.scanAllDimensions(server);
        totalCount = countTotalWork(server, allRegions);
        int totalSkippedEmpty = allRegions.stream()
                .mapToInt(DimensionRegions::skippedEmptyCount)
                .sum();
        if (totalCount == 0) {
            LOGGER.info("No regions found to convert");
            isRunning.set(false);
            return true;
        }
        LOGGER.info("Starting conversion of {} regions across {} dimensions", totalCount, allRegions.size());
        try {
            for (DimensionRegions dimRegions : allRegions) {
                if (isCancelRequested()) {
                    LOGGER.info("Conversion cancelled, skipping remaining dimensions");
                    break;
                }
                convertDimension(server, dimRegions, false);
            }
        } finally {
            isRunning.set(false);
            LOGGER.info(
                    "Conversion completed: {}/{} regions converted, {} skipped (empty MCA at scan)",
                    convertedCountAtomic.get(),
                    totalCount,
                    totalSkippedEmpty);
        }
        return true;
    }

    private static void convertDimension(MinecraftServer server, DimensionRegions dimRegions, boolean force) {
        ServerLevel level = server.getLevel(dimRegions.dimension());
        if (level == null) {
            LOGGER.error("Level not loaded");
            return;
        }

        String fullDimId = dimRegions.dimension().location().toString();
        String dimPath = dimRegions.dimension().location().getPath();

        DimensionScanConfig scanConfig = ModConfig.SERVER.getConfigForDimension(dimPath);

        String dimFolderName = PathMapping.toServerFolderName(fullDimId);
        Path regionDir = RegionScanner.getRegionDir(level);
        Path baseOutputDir = MapSyncer.CACHE_DIR.resolve(dimFolderName);

        if (regionDir == null) {
            LOGGER.error("Region directory not found for dimension: {}", dimFolderName);
            return;
        }

        DimensionInfo dimTypeInfo = ApiHelper.fromDimensionType(level.dimensionType());
        List<RegionScanPass> passes = RegionGenerationPlanner.plan(scanConfig, dimTypeInfo);

        try {
            for (RegionScanPass pass : passes) {
                Files.createDirectories(ModConfig.outputDir(baseOutputDir, pass.caveLayer()));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create output directories under: {}", baseOutputDir, e);
            return;
        }

        LOGGER.info(
                "Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, logicalTop={}, passes={}",
                dimPath,
                dimTypeInfo.hasSkylight(),
                dimTypeInfo.hasCeiling(),
                dimTypeInfo.minY(),
                dimTypeInfo.logicalTopY(),
                passes.size());

        List<RegionCoords> needsUpdate = force
                ? dimRegions.regions()
                : filterRegionsNeedingUpdate(dimRegions.fileEntries(), baseOutputDir, passes);
        List<RegionCoords> regions = dimRegions.regions();

        totalCount = regions.size() * passes.size();
        LOGGER.info(
                "Dimension {}: {} total regions, {} need update, {} passes/region (force={})",
                dimPath,
                regions.size(),
                needsUpdate.size(),
                passes.size(),
                force);

        ConcurrentLinkedQueue<RegionCoords> failedRegions = new ConcurrentLinkedQueue<>();
        processedCountAtomic.set(0);
        skippedCount.set(0);
        convertedCountAtomic.set(0);
        skippedEmptyContentCount.set(0);

        runConversionTasks(
                needsUpdate, regions, regionDir, baseOutputDir, dimFolderName, dimTypeInfo, passes, failedRegions, true);

        if (!force) {
            runNewRegionTasks(
                    regions,
                    new HashSet<>(needsUpdate),
                    regionDir,
                    baseOutputDir,
                    dimFolderName,
                    dimTypeInfo,
                    passes,
                    failedRegions);
        }

        if (!failedRegions.isEmpty()) {
            LOGGER.warn("Failed to convert {} regions", failedRegions.size());
            for (RegionCoords coords : failedRegions) {
                LOGGER.warn("Failed region: ({}, {})", coords.x(), coords.z());
            }
        }

        LOGGER.info(
                "Dimension {} completed: {} total, {} converted, {} skipped (unchanged), {} skipped (empty MCA at scan), {} skipped (empty content), {} failed",
                dimPath,
                regions.size(),
                convertedCountAtomic.get(),
                skippedCount.get(),
                dimRegions.skippedEmptyCount(),
                skippedEmptyContentCount.get(),
                failedRegions.size());

        String friendlyName =
                friendlyDimensionName(dimRegions.dimension().location().toString());
        completedDimensions.add(friendlyName);
    }

    private static int countTotalWork(MinecraftServer server, List<DimensionRegions> allRegions) {
        int total = 0;
        for (DimensionRegions dimRegions : allRegions) {
            ServerLevel level = server.getLevel(dimRegions.dimension());
            if (level == null) {
                continue;
            }
            String dimPath = dimRegions.dimension().location().getPath();
            DimensionScanConfig scanConfig = ModConfig.SERVER.getConfigForDimension(dimPath);
            DimensionInfo dimTypeInfo = ApiHelper.fromDimensionType(level.dimensionType());
            int passCount = RegionGenerationPlanner.countPasses(scanConfig, dimTypeInfo);
            total += dimRegions.regions().size() * passCount;
        }
        return total;
    }

    private static List<RegionCoords> filterRegionsNeedingUpdate(
            List<RegionScanner.RegionFileEntry> fileEntries, Path baseOutputDir, List<RegionScanPass> passes) {
        List<RegionCoords> needsUpdate = new ArrayList<>();

        for (RegionScanner.RegionFileEntry entry : fileEntries) {
            RegionCoords coords = entry.coords();
            boolean needs = false;
            for (RegionScanPass pass : passes) {
                Path zipPath = ModConfig.outputDir(baseOutputDir, pass.caveLayer())
                        .resolve(coords.x() + "_" + coords.z() + ".zip");
                if (!Files.exists(zipPath)) {
                    needs = true;
                    break;
                }
                try {
                    if (entry.lastModifiedMillis()
                            > Files.getLastModifiedTime(zipPath).toMillis()) {
                        needs = true;
                        break;
                    }
                } catch (IOException e) {
                    needs = true;
                    break;
                }
            }
            if (needs) {
                needsUpdate.add(coords);
            }
        }

        return needsUpdate;
    }

    private static void runConversionTasks(
            List<RegionCoords> coordsToProcess,
            List<RegionCoords> allRegions,
            Path regionDir,
            Path baseOutputDir,
            String dimFolderName,
            DimensionInfo dimTypeInfo,
            List<RegionScanPass> passes,
            ConcurrentLinkedQueue<RegionCoords> failedRegions,
            boolean logProgress) {

        Set<RegionCoords> validRegions = new HashSet<>(allRegions);

        for (RegionCoords coords : coordsToProcess) {
            if (isCancelRequested()) return;
            if (!validRegions.contains(coords)) continue;

            convertRegionMultiPasses(
                    coords,
                    regionDir,
                    baseOutputDir,
                    dimFolderName,
                    dimTypeInfo,
                    passes,
                    failedRegions,
                    logProgress,
                    "Converted");
        }
    }

    private static void runNewRegionTasks(
            List<RegionCoords> allRegions,
            Set<RegionCoords> processedRegions,
            Path regionDir,
            Path baseOutputDir,
            String dimFolderName,
            DimensionInfo dimTypeInfo,
            List<RegionScanPass> passes,
            ConcurrentLinkedQueue<RegionCoords> failedRegions) {

        for (RegionCoords coords : allRegions) {
            if (isCancelRequested()) return;
            if (processedRegions.contains(coords)) continue;

            boolean allExist = true;
            for (RegionScanPass pass : passes) {
                Path outputDir = ModConfig.outputDir(baseOutputDir, pass.caveLayer());
                if (!XaeroWriter.regionFileExists(outputDir, coords.x(), coords.z())) {
                    allExist = false;
                    break;
                }
            }
            if (allExist) {
                processedCountAtomic.addAndGet(passes.size());
                skippedCount.incrementAndGet();
                LOGGER.debug("Skipped region ({}, {}): all pass outputs exist", coords.x(), coords.z());
                continue;
            }

            convertRegionMultiPasses(
                    coords,
                    regionDir,
                    baseOutputDir,
                    dimFolderName,
                    dimTypeInfo,
                    passes,
                    failedRegions,
                    true,
                    "Generated new");
        }
    }

    private static void convertRegionMultiPasses(
            RegionCoords coords,
            Path regionDir,
            Path baseOutputDir,
            String dimFolderName,
            DimensionInfo dimTypeInfo,
            List<RegionScanPass> passes,
            ConcurrentLinkedQueue<RegionCoords> failedRegions,
            boolean logProgress,
            String logPrefix) {

        if (isCancelRequested()) {
            LOGGER.debug("Conversion cancelled, skipping region ({}, {})", coords.x(), coords.z());
            return;
        }

        Path mcaPath = regionDir.resolve("r." + coords.x() + "." + coords.z() + ".mca");

        if (!com.mapsyncer.mca.McaReader.hasAnyChunk(mcaPath)) {
            for (RegionScanPass pass : passes) {
                Path outputDir = ModConfig.outputDir(baseOutputDir, pass.caveLayer());
                String relativePath = ModConfig.relativePath(dimFolderName, pass.caveLayer(), coords.x(), coords.z());
                purgeGeneratedArtifacts(outputDir, coords.x(), coords.z(), relativePath);
            }
            ManifestServer.get().invalidate();
            skippedEmptyContentCount.incrementAndGet();
            if (logProgress) {
                processedCountAtomic.addAndGet(passes.size());
            }
            return;
        }

        List<LayerConvertedRegion> converted = RegionConverter.convertRegionMulti(
                mcaPath, coords.x(), coords.z(), dimTypeInfo, passes, BlockPropertyResolver.INSTANCE);

        if (converted.isEmpty()) {
            failedRegions.add(coords);
            return;
        }

        boolean anyWritten = false;
        boolean anyPurged = false;
        boolean anyFailed = false;
        for (int i = 0; i < passes.size(); i++) {
            RegionScanPass pass = passes.get(i);
            LayerConvertedRegion layer = i < converted.size() ? converted.get(i) : null;
            Path outputDir = ModConfig.outputDir(baseOutputDir, pass.caveLayer());
            String relativePath = ModConfig.relativePath(dimFolderName, pass.caveLayer(), coords.x(), coords.z());

            ConvertedRegion single =
                    layer == null ? null : new ConvertedRegion(layer.regionX(), layer.regionZ(), layer.xaeroData());

            if (single == null || single.xaeroData() == null || single.xaeroData().length == 0) {
                purgeGeneratedArtifacts(outputDir, coords.x(), coords.z(), relativePath);
                anyPurged = true;
                if (logProgress) {
                    processedCountAtomic.incrementAndGet();
                }
                continue;
            }

            try {
                XaeroWriter.writeRegionFile(outputDir, single);
                anyWritten = true;
                if (logProgress) {
                    int convertedSoFar = convertedCountAtomic.incrementAndGet();
                    processedCountAtomic.incrementAndGet();
                    String layerLabel = pass.isSurfaceLayer() ? "surface" : String.valueOf(pass.caveLayer());
                    LOGGER.info(
                            "{} region ({}, {}) layer={}: {}/{}",
                            logPrefix,
                            coords.x(),
                            coords.z(),
                            layerLabel,
                            convertedSoFar,
                            totalCount);
                }
            } catch (IOException e) {
                LOGGER.error("Failed to write region file for layer {}", pass.caveLayer(), e);
                anyFailed = true;
            }
        }

        if (anyWritten || anyPurged) {
            ManifestServer.get().invalidate();
        }
        if (anyFailed) {
            failedRegions.add(coords);
        } else if (!anyWritten) {
            skippedEmptyContentCount.incrementAndGet();
        }
    }

    public record IncrementalScanSnapshot(
            String dimPath,
            String dimFolderName,
            Path regionDir,
            Path baseOutputDir,
            DimensionInfo dimTypeInfo,
            List<RegionScanPass> passes) {}

    public static List<IncrementalScanSnapshot> buildIncrementalScanSnapshots(MinecraftServer server) {
        List<DimensionRegions> allRegions = RegionScanner.scanAllDimensions(server);
        List<IncrementalScanSnapshot> snapshots = new ArrayList<>();

        for (DimensionRegions dimRegions : allRegions) {
            ServerLevel level = server.getLevel(dimRegions.dimension());
            if (level == null) {
                continue;
            }

            String fullDimId = dimRegions.dimension().location().toString();
            String dimPath = dimRegions.dimension().location().getPath();

            DimensionScanConfig scanConfig = ModConfig.SERVER.getConfigForDimension(dimPath);
            String dimFolderName = PathMapping.toServerFolderName(fullDimId);

            Path regionDir = RegionScanner.getRegionDir(level);
            if (regionDir == null) {
                continue;
            }

        Path baseOutputDir = MapSyncer.CACHE_DIR.resolve(dimFolderName);
            DimensionInfo dimTypeInfo = ApiHelper.fromDimensionType(level.dimensionType());
            List<RegionScanPass> passes = RegionGenerationPlanner.plan(scanConfig, dimTypeInfo);

            snapshots.add(
                    new IncrementalScanSnapshot(dimPath, dimFolderName, regionDir, baseOutputDir, dimTypeInfo, passes));
        }

        return snapshots;
    }

    public static void performIncrementalScan(MinecraftServer server) {
        List<IncrementalScanSnapshot> snapshots;
        try {
            snapshots =
                    server.submit(() -> buildIncrementalScanSnapshots(server)).get(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Incremental scan snapshot interrupted");
            return;
        } catch (java.util.concurrent.TimeoutException e) {
            LOGGER.error("Timed out building incremental scan snapshot on server thread");
            return;
        } catch (ExecutionException e) {
            LOGGER.error("Failed to build incremental scan snapshot on server thread", e.getCause());
            return;
        }
        performIncrementalScan(snapshots);
    }

    public static void performIncrementalScan(List<IncrementalScanSnapshot> snapshots) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.debug("Conversion already in progress, skipping incremental scan");
            return;
        }
        cancelRequested.set(false);

        try {
            int totalUpdated = 0;
            totalCount = 0;
            processedCountAtomic.set(0);
            ConcurrentLinkedQueue<RegionCoords> failedRegions = new ConcurrentLinkedQueue<>();

            for (IncrementalScanSnapshot snapshot : snapshots) {
                if (isCancelRequested()) {
                    LOGGER.info("Incremental scan cancelled, skipping remaining dimensions");
                    break;
                }
                String dimPath = snapshot.dimPath();
                Path regionDir = snapshot.regionDir();
                Path baseOutputDir = snapshot.baseOutputDir();
                String dimFolderName = snapshot.dimFolderName();
                List<RegionScanPass> passes = snapshot.passes();
                DimensionInfo dimTypeInfo = snapshot.dimTypeInfo();

                java.util.List<RegionCoords> needsUpdate =
                        filterRegionsNeedingUpdate(RegionScanner.listRegionFiles(regionDir), baseOutputDir, passes);

                if (needsUpdate.isEmpty()) {
                    LOGGER.debug("No updates needed for dimension {}", dimPath);
                    continue;
                }

                LOGGER.info(
                        "Dimension {}: {} regions need incremental update (passes={})",
                        dimPath,
                        needsUpdate.size(),
                        passes.size());

                try {
                    for (RegionScanPass pass : passes) {
                        Files.createDirectories(ModConfig.outputDir(baseOutputDir, pass.caveLayer()));
                    }
                } catch (IOException e) {
                    LOGGER.error("Failed to create output directories: {}", baseOutputDir, e);
                    continue;
                }

                totalCount += needsUpdate.size() * passes.size();
                int failuresBefore = failedRegions.size();
                runConversionTasks(
                        needsUpdate,
                        needsUpdate,
                        regionDir,
                        baseOutputDir,
                        dimFolderName,
                        dimTypeInfo,
                        passes,
                        failedRegions,
                        true);
                totalUpdated += needsUpdate.size() - (failedRegions.size() - failuresBefore);
            }

            if (totalUpdated > 0) {
                LOGGER.info("Incremental scan completed: {} regions updated", totalUpdated);
            }
        } finally {
            isRunning.set(false);
        }
    }

    public static boolean isRunning() {
        return isRunning.get();
    }

    public static boolean requestCancel() {
        if (!isRunning.get()) {
            LOGGER.info("Cancel requested but no conversion is running");
            return false;
        }
        cancelRequested.set(true);
        LOGGER.info("Cancellation requested for ongoing conversion");
        return true;
    }

    private static boolean isCancelRequested() {
        return cancelRequested.get();
    }

    public static int getProcessedCount() {
        return processedCountAtomic.get();
    }

    public static int getTotalCount() {
        return totalCount;
    }

    public static List<String> getCompletedDimensions() {
        return completedDimensions;
    }

    public record DimensionCacheStats(String dimension, int regionCount, long sizeBytes) {

        public double sizeMB() {
            return sizeBytes / (1024.0 * 1024.0);
        }
    }

    private static String friendlyDimensionName(String dimPath) {
        if (dimPath == null || dimPath.isEmpty()) {
            return "minecraft:overworld";
        }
        if (dimPath.startsWith("minecraft:")) {
            return dimPath;
        }
        int dollarIndex = dimPath.indexOf('$');
        if (dollarIndex > 0) {
            String namespace = dimPath.substring(0, dollarIndex);
            String path = dimPath.substring(dollarIndex + 1).replace('%', '/').replace(',', '.');
            return namespace + ":" + path;
        }
        if (dimPath.contains(":")) {
            return dimPath;
        }
        return "minecraft:" + dimPath;
    }

    public static List<DimensionCacheStats> getCacheStats() {
        List<DimensionCacheStats> stats = new ArrayList<>();

        if (!Files.exists(MapSyncer.CACHE_DIR)) {
            return stats;
        }

        try (DirectoryStream<Path> dimDirs = Files.newDirectoryStream(MapSyncer.CACHE_DIR)) {
            for (Path dimDir : dimDirs) {
                if (!dimDir.toFile().isDirectory()) continue;

                String dimName = dimDir.getFileName().toString();
                String friendlyName = friendlyDimensionName(dimName);

                int regionCount = 0;
                long totalSize = 0;

                try (Stream<Path> files = Files.walk(dimDir)) {
                    List<Path> zipFiles =
                            files.filter(p -> p.toString().endsWith(".zip")).toList();

                    regionCount = zipFiles.size();
                    totalSize = zipFiles.stream()
                            .mapToLong(p -> {
                                try {
                                    return Files.size(p);
                                } catch (IOException e) {
                                    return 0;
                                }
                            })
                            .sum();
                }

                if (regionCount > 0) {
                    stats.add(new DimensionCacheStats(friendlyName, regionCount, totalSize));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to get cache stats", e);
        }

        return stats;
    }

    private static void purgeGeneratedArtifacts(Path outputDir, int regionX, int regionZ, String relativePath) {
        Path zip = outputDir.resolve(regionX + "_" + regionZ + ".zip");
        Path temp = outputDir.resolve(regionX + "_" + regionZ + ".zip.temp");
        try {
            Files.deleteIfExists(zip);
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete empty region zip {}: {}", zip, e.getMessage());
        }
        LOGGER.debug("Purged empty region artifacts for {}", relativePath);
    }
}
