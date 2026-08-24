package com.mapsyncer.server;

import com.mapsyncer.mca.RegionConverter;
import com.mapsyncer.mca.RegionScanner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        processed = 0;
        total = 0;

        try {
            List<RegionScanner.RegionEntry> entries = RegionScanner.scan(server);
            total = entries.size();

            Map<String, List<RegionScanner.RegionEntry>> byDim = entries.stream()
                    .collect(Collectors.groupingBy(
                            RegionScanner.RegionEntry::dimId,
                            LinkedHashMap::new,
                            Collectors.toList()));

            for (Map.Entry<String, List<RegionScanner.RegionEntry>> dim : byDim.entrySet()) {
                if (!isRunning.get()) {
                    break;
                }
                LOGGER.info(
                        "Converting dimension {} ({} regions)",
                        dim.getKey(),
                        dim.getValue().size());
                for (RegionScanner.RegionEntry entry : dim.getValue()) {
                    if (!isRunning.get()) {
                        break;
                    }
                    try {
                        RegionConverter.convert(entry, BlockPropertyResolver.INSTANCE);
                    } catch (IOException e) {
                        LOGGER.warn(
                                "Failed to convert region ({}, {})",
                                entry.coords().x(),
                                entry.coords().z(),
                                e);
                    }
                    processed++;
                }
                if (isRunning.get()) {
                    completed.add(dim.getKey());
                    LOGGER.info("Dimension {} conversion complete", dim.getKey());
                }
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
