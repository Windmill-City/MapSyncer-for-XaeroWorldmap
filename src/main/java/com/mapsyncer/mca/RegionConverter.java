package com.mapsyncer.mca;

import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class RegionConverter {

    private static final Logger LOGGER = LogManager.getLogger(RegionConverter.class);

    public static void convert(RegionScanner.Region entry) throws IOException {
        LOGGER.warn("RegionConverter is not implemented yet, skipping region ({}, {})", entry.X(), entry.Z());
    }
}
