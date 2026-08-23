package com.mapsyncer.client;

import com.mapsyncer.network.RegionData;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientSyncWriteQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientSyncWriteQueue.class);

    private static final int IO_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);

    private static volatile ExecutorService executor = null;

    private static final AtomicInteger pendingWrites = new AtomicInteger(0);

    private static ExecutorService getExecutor() {
        ExecutorService current = executor;
        if (current != null && !current.isShutdown()) {
            return current;
        }
        synchronized (ClientSyncWriteQueue.class) {
            current = executor;
            if (current == null || current.isShutdown()) {
                current = Executors.newFixedThreadPool(IO_THREADS, r -> {
                    Thread t = new Thread(r, "mapsyncer-sync-io");
                    t.setDaemon(true);
                    return t;
                });
                executor = current;
                LOGGER.debug("ClientSyncWriteQueue executor (re)created");
            }
            return current;
        }
    }

    public static boolean hasPendingWrites() {
        return pendingWrites.get() > 0;
    }

    public static void submit(RegionData chunk, Consumer<XaeroMapWriter.RegionWriteResult> callback) {
        pendingWrites.incrementAndGet();
        Runnable task = () -> {
            XaeroMapWriter.RegionWriteResult result = null;
            try {
                result = XaeroMapWriter.writeChunkData(chunk);
                if (result != null) {
                    XaeroMapWriter.clearRegionCacheFiles(
                            result.mwDir(),
                            new XaeroMapWriter.RegionCoord(
                                    chunk.ref.regionX(), chunk.ref.regionZ(), chunk.ref.caveLayer()));
                }
            } catch (Exception e) {
                LOGGER.error("Async region write failed for ({}, {})", chunk.ref.regionX(), chunk.ref.regionZ(), e);
            } finally {
                pendingWrites.decrementAndGet();
                invokeCallback(chunk, callback, result);
            }
        };

        try {
            getExecutor().execute(task);
        } catch (RejectedExecutionException e) {
            pendingWrites.decrementAndGet();
            LOGGER.error(
                    "Sync write queue rejected task for ({}, {}), executor shutdown?",
                    chunk.ref.regionX(),
                    chunk.ref.regionZ(),
                    e);
            invokeCallback(chunk, callback, null);
        }
    }

    private static void invokeCallback(
            RegionData chunk,
            Consumer<XaeroMapWriter.RegionWriteResult> callback,
            XaeroMapWriter.RegionWriteResult result) {
        try {
            callback.accept(result);
        } catch (Exception e) {
            LOGGER.error("Sync write callback failed for ({}, {})", chunk.ref.regionX(), chunk.ref.regionZ(), e);
        }
    }

    public static void shutdown() {
        synchronized (ClientSyncWriteQueue.class) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
        pendingWrites.set(0);
        LOGGER.debug("ClientSyncWriteQueue shutdown");
    }
}
