package com.mapsyncer.client;

public final class ClientSyncSession {

    private static final ClientSyncSession INSTANCE = new ClientSyncSession();

    public static final long STALE_TIMEOUT_MS = 30 * 60 * 1000L;

    private volatile int generation = 0;
    private volatile boolean receiving = false;
    private volatile long startedAt = 0;
    private volatile boolean reflectionFailed = false;

    private ClientSyncSession() {}

    public static ClientSyncSession get() {
        return INSTANCE;
    }

    public int generation() {
        return generation;
    }

    public boolean isReceiving() {
        return receiving;
    }

    public boolean reflectionFailed() {
        return reflectionFailed;
    }

    public boolean isCurrent(int gen) {
        return gen == generation;
    }

    public boolean isStale() {
        if (!receiving || startedAt == 0) {
            return false;
        }
        return System.currentTimeMillis() - startedAt > STALE_TIMEOUT_MS;
    }

    public void invalidate() {
        generation++;
        reset();
    }

    public void begin() {
        receiving = true;
        startedAt = System.currentTimeMillis();
        reflectionFailed = false;
    }

    public void touch() {
        if (receiving) {
            startedAt = System.currentTimeMillis();
        }
    }

    public void markReflectionFailed() {
        reflectionFailed = true;
    }

    public void complete() {
        reset();
    }

    private void reset() {
        receiving = false;
        startedAt = 0;
        reflectionFailed = false;
    }
}
