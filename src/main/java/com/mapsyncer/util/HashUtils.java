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

    public static boolean isValidHash(String hash) {
        return hash != null && !hash.isEmpty() && !DEFAULT_HASH.equals(hash);
    }

    public static boolean isValidRegionZip(byte[] data) {
        if (data == null || data.length < 22) {
            return false;
        }
        if (data[0] != 0x50 || data[1] != 0x4B || data[2] != 0x03 || data[3] != 0x04) {
            return false;
        }
        for (int i = data.length - 22; i >= 0; i--) {
            if (data[i] == 0x50 && data[i + 1] == 0x4B && data[i + 2] == 0x05 && data[i + 3] == 0x06) {
                return true;
            }
        }
        return false;
    }

    public static boolean isValidRegionZip(Path filePath) {
        if (filePath == null || !Files.exists(filePath)) {
            return false;
        }
        long size;
        try {
            size = Files.size(filePath);
        } catch (IOException e) {
            return false;
        }
        if (size < 22) {
            return false;
        }

        int tailLength = (int) Math.min(size, 22L + 0xFFFF);
        byte[] header = new byte[4];
        byte[] tail = new byte[tailLength];
        int total = 0;
        try (InputStream is = Files.newInputStream(filePath)) {
            if (is.read(header) < 4) {
                return false;
            }
            long toSkip = size - tailLength;
            long skipped = 0;
            while (skipped < toSkip) {
                long s = is.skip(toSkip - skipped);
                if (s <= 0) {
                    if (is.read() == -1) {
                        return false;
                    }
                    skipped++;
                } else {
                    skipped += s;
                }
            }
            while (total < tailLength) {
                int r = is.read(tail, total, tailLength - total);
                if (r <= 0) {
                    break;
                }
                total += r;
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read zip for validation: {}", filePath, e);
            return false;
        }
        if (total < 22) {
            return false;
        }

        if (header[0] != 0x50 || header[1] != 0x4B || header[2] != 0x03 || header[3] != 0x04) {
            return false;
        }
        for (int i = total - 22; i >= 0; i--) {
            if (tail[i] == 0x50 && tail[i + 1] == 0x4B && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
                return true;
            }
        }
        return false;
    }
}
