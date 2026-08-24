package com.mapsyncer.mca;

import com.mapsyncer.network.RegionData;
import com.mapsyncer.util.PathUtils;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class XaeroWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroWriter.class);

    public static void cleanStaleFiles(Path rootDir) {
        if (!Files.exists(rootDir)) return;
        try (var stream = Files.walk(rootDir)) {
            long deleted = stream.filter(p -> p.getFileName().toString().endsWith(".zip.temp"))
                    .filter(p -> {
                        try {
                            boolean removed = Files.deleteIfExists(p);
                            if (removed) {
                                LOGGER.debug("Cleaned stale temp file: {}", p);
                            }
                            return removed;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .count();
            if (deleted > 0) {
                LOGGER.info("Cleaned {} stale .temp files from {}", deleted, rootDir);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan for stale temp files in {}", rootDir, e);
        }
    }

    public static void writeRegionFile(RegionData region) throws IOException {
        Path dimDir = PathUtils.getDimDirServer(region.ref.dimId());
        Path outDir =
                region.ref.isSurface() ? dimDir : dimDir.resolve("caves").resolve(String.valueOf(region.ref.cave()));
        Files.createDirectories(outDir);

        String fileName = region.ref.regionX() + "_" + region.ref.regionZ();
        Path tempFile = outDir.resolve(fileName + ".zip.temp");
        Path finalFile = outDir.resolve(fileName + ".zip");

        try (OutputStream fileOut = Files.newOutputStream(tempFile);
                ZipOutputStream zos = new ZipOutputStream(fileOut)) {
            ZipEntry entry = new ZipEntry("region.xaero");
            zos.putNextEntry(entry);
            zos.write(region.data);
            zos.closeEntry();
        }

        Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
    }
}
