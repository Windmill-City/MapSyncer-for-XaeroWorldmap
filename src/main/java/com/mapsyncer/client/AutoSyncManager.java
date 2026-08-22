package com.mapsyncer.client;

public class AutoSyncManager {

    private static volatile boolean active = false;

    public static void markStarted() {
        active = true;
    }

    public static void markComplete() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    public static void cancel() {
        active = false;
    }
}
