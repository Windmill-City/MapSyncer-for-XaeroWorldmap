package com.mapsyncer.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HashUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(HashUtils.class);

    public static final String DEFAULT_HASH = "00000000";

    public static String computeFileHash(Path filePath) {
        if (filePath == null || !Files.exists(filePath)) {
            return DEFAULT_HASH;
        }

        CRC32 crc32 = new CRC32();
        byte[] buffer = new byte[8192];

        try (InputStream is = Files.newInputStream(filePath)) {
            int len;
            while ((len = is.read(buffer)) != -1) {
                crc32.update(buffer, 0, len);
            }
            return String.format("%08x", crc32.getValue());
        } catch (IOException e) {
            LOGGER.warn("Failed to compute hash for {}", filePath, e);
            return DEFAULT_HASH;
        }
    }
}
