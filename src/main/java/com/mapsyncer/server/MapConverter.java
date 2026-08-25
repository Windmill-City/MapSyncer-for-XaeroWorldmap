package com.mapsyncer.server;

import com.mapsyncer.mca.Plan;
import com.mapsyncer.mca.RegionConverter;
import com.mapsyncer.mca.RegionScanner;
import com.mapsyncer.mca.RegionScanner.Region;
import com.mapsyncer.util.PathUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

class MapConverter {

    private static final Logger LOGGER = LogManager.getLogger(MapConverter.class);

    private static final AtomicBoolean isRunning = new AtomicBoolean(false);

    private static volatile int processed = 0;

    private static volatile int total = 0;

    private static final List<String> completed = new CopyOnWriteArrayList<>();

    static boolean generate(MinecraftServer server) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.warn("Conversion already in progress, rejecting...");
            return false;
        }
        LOGGER.info("Conversion start...");
        completed.clear();
        processed = 0;
        total = 0;

        try {
            for (ServerLevel level : server.getAllLevels()) {
                if (!isRunning.get()) break;

                var dimId = PathUtils.getDimId(level);
                Plan plan = Plan.getPlan(dimId);
                if (!plan.surface() && plan.caves().isEmpty()) {
                    LOGGER.debug("No layer plan for dimension {}, skipping", dimId);
                    continue;
                }

                List<Region> entries = RegionScanner.scan(level);
                if (entries.isEmpty()) continue;

                total += entries.size();
                LOGGER.info("Converting dimension {} ({} regions)", dimId, entries.size());

                for (Region entry : entries) {
                    if (!isRunning.get()) break;

                    try {
                        RegionConverter.convert(entry, plan);
                    } catch (IOException e) {
                        LOGGER.warn("Failed to convert region ({}, {})", entry.X(), entry.Z(), e);
                    }
                    processed++;
                }
                completed.add(dimId);
                LOGGER.info("Dimension {} conversion complete", dimId);
            }
        } catch (Throwable e) {
            LOGGER.error("Conversion failed", e);
        } finally {
            isRunning.set(false);
            LOGGER.info("Conversion stopped");
        }
        return true;
    }

    static boolean isRunning() {
        return isRunning.get();
    }

    static boolean requestCancel() {
        if (!isRunning.compareAndSet(true, false)) {
            return false;
        }
        LOGGER.info("Cancellation requested for ongoing conversion");
        return true;
    }

    static int getProcessed() {
        return processed;
    }

    static int getTotal() {
        return total;
    }

    static List<String> getCompleted() {
        return Collections.unmodifiableList(new ArrayList<>(completed));
    }
}
