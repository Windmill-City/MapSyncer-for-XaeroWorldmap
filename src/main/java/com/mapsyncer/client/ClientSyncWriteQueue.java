package com.mapsyncer.client;

import com.mapsyncer.network.RegionData;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientSyncWriteQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientSyncWriteQueue.class);

    private static final ExecutorService executor = Util.ioPool();

    private static final AtomicInteger pendingWrites = new AtomicInteger(0);

    public static boolean hasPendingWrites() {
        return pendingWrites.get() > 0;
    }

    public static void submit(RegionData chunk, Consumer<Boolean> callback) {
        pendingWrites.incrementAndGet();
        Runnable task = () -> {
            boolean success = false;
            try {
                success = XaeroMapWriter.writeChunkData(chunk);
            } catch (Exception e) {
                LOGGER.error("Async region write failed for ({}, {})", chunk.ref.regionX(), chunk.ref.regionZ(), e);
            } finally {
                pendingWrites.decrementAndGet();
                invokeCallback(chunk, callback, success);
            }
        };

        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            pendingWrites.decrementAndGet();
            LOGGER.error(
                    "Sync write queue rejected task for ({}, {}), executor shutdown?",
                    chunk.ref.regionX(),
                    chunk.ref.regionZ(),
                    e);
            invokeCallback(chunk, callback, false);
        }
    }

    private static void invokeCallback(RegionData chunk, Consumer<Boolean> callback, boolean success) {
        try {
            callback.accept(success);
        } catch (Exception e) {
            LOGGER.error("Sync write callback failed for ({}, {})", chunk.ref.regionX(), chunk.ref.regionZ(), e);
        }
    }
}
