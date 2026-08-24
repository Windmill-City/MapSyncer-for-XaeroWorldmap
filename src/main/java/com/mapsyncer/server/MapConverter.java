package com.mapsyncer.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
        completed.clear();

        try {
            LOGGER.warn("MapConverter.generate is not implemented");
        } catch (Throwable e) {
            LOGGER.error("Map generate failed", e);
        } finally {
            isRunning.set(false);
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
