package com.mapsyncer.client;

import com.mapsyncer.network.RegionData;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class XaeroWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroWriter.class);

    public static boolean writeChunkData(RegionData chunk) {
        String xaeroDim = XaeroBridge.getDimensionName(chunk.ref.dimId());
        if (xaeroDim == null) {
            LOGGER.error(
                    "Unable to resolve Xaero dimension name for {}, skipping region ({}, {})",
                    chunk.ref.dimId(),
                    chunk.ref.regionX(),
                    chunk.ref.regionZ());
            return false;
        }

        Path serverDir = XaeroBridge.getCurrentServerDirectory();
        if (serverDir == null) {
            LOGGER.error(
                    "Unable to resolve server directory, skipping region ({}, {}) dim={}",
                    chunk.ref.regionX(),
                    chunk.ref.regionZ(),
                    chunk.ref.dimId());
            return false;
        }

        String worldId = XaeroBridge.getCurrentWorldId();
        if (worldId == null || worldId.isEmpty()) {
            LOGGER.error(
                    "Unable to resolve current world id from Xaero, skipping region ({}, {}) dim={}",
                    chunk.ref.regionX(),
                    chunk.ref.regionZ(),
                    chunk.ref.dimId());
            return false;
        }

        Path dimDir = serverDir.resolve(xaeroDim);
        Path mwDir = dimDir.resolve("mw$" + worldId);

        Path targetDir;
        if (chunk.ref.isSurface()) {
            targetDir = mwDir;
        } else {
            targetDir = mwDir.resolve("caves").resolve(String.valueOf(chunk.ref.cave()));
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
                    chunk.ref.isSurface() ? "surface" : chunk.ref.cave(),
                    chunk.data.length);
        } catch (IOException e) {
            LOGGER.error("Failed to write map file: {}", outputFile, e);
            return false;
        }

        Minecraft.getInstance()
                .execute(() -> XaeroBridge.loadRegion(chunk.ref.regionX(), chunk.ref.regionZ(), chunk.ref.cave()));

        return true;
    }
}
