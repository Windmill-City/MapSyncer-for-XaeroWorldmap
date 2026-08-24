package com.mapsyncer.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mapsyncer.mca.RegionScanner;

import net.minecraft.server.MinecraftServer;

public class MapConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapConverter.class);

    private static final AtomicBoolean isRunning = new AtomicBoolean(false);

    private static volatile int processed = 0;

    private static volatile int total = 0;

    private static final List<String> completed = new CopyOnWriteArrayList<>();

    public static boolean generate(MinecraftServer server) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.warn("Conversion already in progress, rejecting...");
            return false;
        }
        LOGGER.info("Conversion start...");
        completed.clear();

        try {
            var regions = RegionScanner.scan(server);
            for (RegionScanner.Regions r : regions) {
                LOGGER.info(
                        "Dimension: {}, regionDir: {}, regions found: {}",
                        r.dimId(),
                        r.regionDir(),
                        r.entries().size());
            }
        } catch (Throwable e) {
            LOGGER.error("Conversion failed", e);
        } finally {
            isRunning.set(false);
            LOGGER.info("Conversion stopped");
        }
        return true;
    }

    public static boolean isRunning() {
        return isRunning.get();
    }

    public static boolean requestCancel() {
        if (!isRunning.compareAndSet(true, false)) {
            return false;
        }
        LOGGER.info("Cancellation requested for ongoing conversion");
        return true;
    }

    public static int getProcessed() {
        return processed;
    }

    public static int getTotal() {
        return total;
    }

    public static List<String> getCompleted() {
        return Collections.unmodifiableList(new ArrayList<>(completed));
    }
}
