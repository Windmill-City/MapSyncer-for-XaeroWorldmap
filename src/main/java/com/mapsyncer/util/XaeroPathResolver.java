package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public final class XaeroPathResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroPathResolver.class);

    private XaeroPathResolver() {}

    public static Path getWorldMapDir(Path gameDir) {
        Path modern = gameDir.resolve("xaero").resolve("world-map");
        if (Files.isDirectory(modern)) {
            LOGGER.debug("Detected Xaero WorldMap path: {}", modern);
            return modern;
        }
        Path legacy = gameDir.resolve("XaeroWorldMap");
        if (Files.isDirectory(legacy)) {
            LOGGER.debug("Detected Xaero WorldMap legacy path: {}", legacy);
            return legacy;
        }
        LOGGER.debug("No Xaero WorldMap directory found, defaulting to: {}", modern);
        return modern;
    }
}
