package com.mapsyncer.client;

import com.mapsyncer.network.RegionData;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class AsyncWriter {

    private static final Logger LOGGER = LogManager.getLogger(AsyncWriter.class);

    private static final AtomicInteger pendingWrites = new AtomicInteger(0);

    public static boolean hasPendingWrites() {
        return pendingWrites.get() > 0;
    }

    public static void submit(RegionData chunk, Consumer<Boolean> callback) {
        pendingWrites.incrementAndGet();
        try {
            Util.ioPool().execute(() -> {
                boolean success = false;
                try {
                    success = XaeroWriter.writeChunkData(chunk);
                } catch (Exception e) {
                    LOGGER.error("Async region write failed for ({}, {})", chunk.ref.X(), chunk.ref.Z(), e);
                } finally {
                    pendingWrites.decrementAndGet();
                    callback.accept(success);
                }
            });
        } catch (RejectedExecutionException e) {
            pendingWrites.decrementAndGet();
            LOGGER.error(
                    "Async write queue rejected task for ({}, {}), executor shutdown?",
                    chunk.ref.X(),
                    chunk.ref.Z(),
                    e);
            callback.accept(false);
        }
    }
}
