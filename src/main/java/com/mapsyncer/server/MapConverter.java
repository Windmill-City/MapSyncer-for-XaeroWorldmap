package com.mapsyncer.server;

import com.mapsyncer.mca.LayerPlan;
import com.mapsyncer.mca.RegionConverter;
import com.mapsyncer.mca.RegionConverter.ConvertedRegion;
import com.mapsyncer.mca.RegionConverter.LayerConvertedRegion;
import com.mapsyncer.server.RegionScanner.RegionCoords;
import com.mapsyncer.util.PathUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
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

    private record DimensionRegions(
            ResourceKey<Level> dimension, Path regionDir, List<RegionScanner.RegionEntry> entries) {}

    private static List<DimensionRegions> scanAllDimensions(MinecraftServer server) {
        List<DimensionRegions> result = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimKey = level.dimension();
            if (result.stream().anyMatch(d -> d.dimension().equals(dimKey))) {
                continue;
            }
            RegionScanner.Regions regions = RegionScanner.scanDimension(level);
            if (regions.path() == null) {
                continue;
            }
            result.add(new DimensionRegions(dimKey, regions.path(), regions.entries()));
        }
        return result;
    }

    private static List<RegionCoords> regionCoords(List<RegionScanner.RegionEntry> entries) {
        List<RegionCoords> coords = new ArrayList<>(entries.size());
        for (RegionScanner.RegionEntry entry : entries) {
            coords.add(entry.coords());
        }
        return coords;
    }

    public static boolean generate(MinecraftServer server) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.warn("Conversion already in progress, rejecting...");
            return false;
        }
        cancelRequested.set(false);
        processedCountAtomic.set(0);
        skippedCount.set(0);
        convertedCountAtomic.set(0);
        skippedEmptyContentCount.set(0);
        completedDimensions.clear();

        List<DimensionRegions> allRegions = scanAllDimensions(server);
        totalCount = countTotalWork(server, allRegions);
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
            LOGGER.info("Conversion completed: {}/{} regions converted", convertedCountAtomic.get(), totalCount);
        }
        return true;
    }

    private static void convertDimension(MinecraftServer server, DimensionRegions dimRegions, boolean force) {
        ResourceKey<Level> dimKey = dimRegions.dimension();
        ServerLevel level = server.getLevel(dimKey);
        if (level == null) {
            LOGGER.error("Level not loaded");
            return;
        }

        String fullDimId = dimKey.location().toString();
        String dimPath = dimKey.location().getPath();

        LayerPlan plan = ScanPlanner.getPlan(dimPath);

        String dimFolderName = PathUtils.getDimFolderServer(fullDimId);
        Path regionDir = dimRegions.regionDir();
        Path baseOutputDir = PathUtils.CACHE_DIR.resolve(dimFolderName);

        if (regionDir == null) {
            LOGGER.error("Region directory not found for dimension: {}", dimFolderName);
            return;
        }

        DimensionInfo dimTypeInfo = ApiHelper.fromDimensionType(level.dimensionType());
        List<RegionScanPass> passes = ScanPlanner.plan(plan, dimTypeInfo);

        try {
            for (RegionScanPass pass : passes) {
                Files.createDirectories(
                        pass.isSurface()
                                ? baseOutputDir
                                : baseOutputDir.resolve("caves").resolve(String.valueOf(pass.cave())));
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
                ? regionCoords(dimRegions.entries())
                : filterRegionsNeedingUpdate(dimRegions.entries(), baseOutputDir, passes);
        List<RegionCoords> regions = regionCoords(dimRegions.entries());

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
                needsUpdate,
                regions,
                regionDir,
                baseOutputDir,
                dimFolderName,
                dimTypeInfo,
                passes,
                failedRegions,
                true);

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
                "Dimension {} completed: {} total, {} converted, {} skipped (unchanged), {} skipped (empty content), {} failed",
                dimPath,
                regions.size(),
                convertedCountAtomic.get(),
                skippedCount.get(),
                skippedEmptyContentCount.get(),
                failedRegions.size());

        completedDimensions.add(dimKey.location().toString());
    }

    private static int countTotalWork(MinecraftServer server, List<DimensionRegions> allRegions) {
        int total = 0;
        for (DimensionRegions dimRegions : allRegions) {
            ServerLevel level = server.getLevel(dimRegions.dimension());
            if (level == null) {
                continue;
            }
            String dimPath = dimRegions.dimension().location().getPath();
            LayerPlan plan = ScanPlanner.getPlan(dimPath);
            DimensionInfo dimTypeInfo = ApiHelper.fromDimensionType(level.dimensionType());
            int passCount = ScanPlanner.countPasses(plan, dimTypeInfo);
            total += dimRegions.entries().size() * passCount;
        }
        return total;
    }

    private static List<RegionCoords> filterRegionsNeedingUpdate(
            List<RegionScanner.RegionEntry> fileEntries, Path baseOutputDir, List<RegionScanPass> passes) {
        List<RegionCoords> needsUpdate = new ArrayList<>();

        for (RegionScanner.RegionEntry entry : fileEntries) {
            RegionCoords coords = entry.coords();
            boolean needs = false;
            for (RegionScanPass pass : passes) {
                Path zipPath = (pass.isSurface()
                                ? baseOutputDir
                                : baseOutputDir.resolve("caves").resolve(String.valueOf(pass.cave())))
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
                Path outputDir = pass.isSurface()
                        ? baseOutputDir
                        : baseOutputDir.resolve("caves").resolve(String.valueOf(pass.cave()));
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
                Path outputDir = pass.isSurface()
                        ? baseOutputDir
                        : baseOutputDir.resolve("caves").resolve(String.valueOf(pass.cave()));
                String relativePath = relativePath(dimFolderName, pass.cave(), coords.x(), coords.z());
                purgeGeneratedArtifacts(outputDir, coords.x(), coords.z(), relativePath);
            }
            ManifestServer.invalidate();
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
            Path outputDir = pass.isSurface()
                    ? baseOutputDir
                    : baseOutputDir.resolve("caves").resolve(String.valueOf(pass.cave()));
            String relativePath = relativePath(dimFolderName, pass.cave(), coords.x(), coords.z());

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
                    String layerLabel = pass.isSurface() ? "surface" : String.valueOf(pass.cave());
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
                LOGGER.error("Failed to write region file for layer {}", pass.cave(), e);
                anyFailed = true;
            }
        }

        if (anyWritten || anyPurged) {
            ManifestServer.invalidate();
        }
        if (anyFailed) {
            failedRegions.add(coords);
        } else if (!anyWritten) {
            skippedEmptyContentCount.incrementAndGet();
        }
    }

    public record AutoUpdateScanSnapshot(
            String dimPath,
            String dimFolderName,
            Path regionDir,
            Path baseOutputDir,
            DimensionInfo dimTypeInfo,
            List<RegionScanPass> passes,
            List<RegionScanner.RegionEntry> entries) {}

    public static List<AutoUpdateScanSnapshot> buildAutoUpdateScanSnapshots(MinecraftServer server) {
        List<AutoUpdateScanSnapshot> snapshots = new ArrayList<>();

        for (DimensionRegions dimRegions : scanAllDimensions(server)) {
            ResourceKey<Level> dimKey = dimRegions.dimension();
            ServerLevel level = server.getLevel(dimKey);
            if (level == null) {
                continue;
            }

            String fullDimId = dimKey.location().toString();
            String dimPath = dimKey.location().getPath();

            LayerPlan plan = ScanPlanner.getPlan(dimPath);
            String dimFolderName = PathUtils.getDimFolderServer(fullDimId);

            Path baseOutputDir = PathUtils.CACHE_DIR.resolve(dimFolderName);
            DimensionInfo dimTypeInfo = ApiHelper.fromDimensionType(level.dimensionType());
            List<RegionScanPass> passes = ScanPlanner.plan(plan, dimTypeInfo);

            snapshots.add(new AutoUpdateScanSnapshot(
                    dimPath,
                    dimFolderName,
                    dimRegions.regionDir(),
                    baseOutputDir,
                    dimTypeInfo,
                    passes,
                    dimRegions.entries()));
        }

        return snapshots;
    }

    public static void performScan(MinecraftServer server) {
        List<AutoUpdateScanSnapshot> snapshots;
        try {
            snapshots =
                    server.submit(() -> buildAutoUpdateScanSnapshots(server)).get(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("AutoUpdate scan snapshot interrupted");
            return;
        } catch (TimeoutException e) {
            LOGGER.error("Timed out building AutoUpdate scan snapshot on server thread");
            return;
        } catch (ExecutionException e) {
            LOGGER.error("Failed to build AutoUpdate scan snapshot on server thread", e.getCause());
            return;
        }
        performScan(snapshots);
    }

    public static void performScan(List<AutoUpdateScanSnapshot> snapshots) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.debug("Conversion already in progress, skipping AutoUpdate scan");
            return;
        }
        cancelRequested.set(false);

        try {
            int totalUpdated = 0;
            totalCount = 0;
            processedCountAtomic.set(0);
            ConcurrentLinkedQueue<RegionCoords> failedRegions = new ConcurrentLinkedQueue<>();

            for (AutoUpdateScanSnapshot snapshot : snapshots) {
                if (isCancelRequested()) {
                    LOGGER.info("AutoUpdate scan cancelled, skipping remaining dimensions");
                    break;
                }
                String dimPath = snapshot.dimPath();
                Path regionDir = snapshot.regionDir();
                Path baseOutputDir = snapshot.baseOutputDir();
                String dimFolderName = snapshot.dimFolderName();
                List<RegionScanPass> passes = snapshot.passes();
                DimensionInfo dimTypeInfo = snapshot.dimTypeInfo();

                List<RegionCoords> needsUpdate = filterRegionsNeedingUpdate(snapshot.entries(), baseOutputDir, passes);

                if (needsUpdate.isEmpty()) {
                    LOGGER.debug("No updates needed for dimension {}", dimPath);
                    continue;
                }

                LOGGER.info(
                        "Dimension {}: {} regions need AutoUpdate (passes={})",
                        dimPath,
                        needsUpdate.size(),
                        passes.size());

                try {
                    for (RegionScanPass pass : passes) {
                        Files.createDirectories(
                                pass.isSurface()
                                        ? baseOutputDir
                                        : baseOutputDir.resolve("caves").resolve(String.valueOf(pass.cave())));
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
                LOGGER.info("AutoUpdate scan completed: {} regions updated", totalUpdated);
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
        return Collections.unmodifiableList(completedDimensions);
    }

    private static String relativePath(String xaeroDimName, int caveLayer, int regionX, int regionZ) {
        if (caveLayer == Integer.MAX_VALUE) {
            return xaeroDimName + "/" + regionX + "_" + regionZ;
        }
        return xaeroDimName + "/caves/" + caveLayer + "/" + regionX + "_" + regionZ;
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
