package com.mapsyncer.util;

import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class XaeroPathResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroPathResolver.class);

    public static Path getWorldMapDir(Path gameDir) {
        Path path = gameDir.resolve("xaero").resolve("world-map");
        if (Files.isDirectory(path)) {
            LOGGER.debug("Detected Xaero WorldMap path: {}", path);
            return path;
        }
        LOGGER.debug("No Xaero WorldMap directory found, defaulting to: {}", path);
        return path;
    }
}
