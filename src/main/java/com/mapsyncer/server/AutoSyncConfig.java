package com.mapsyncer.server;

import com.mapsyncer.config.UpdateMode;

public final class AutoSyncConfig {

    private AutoSyncConfig() {}

    public static int computeInterval(UpdateMode mode) {
        switch (mode) {
            case DISABLED:  return 0;
            case SCHEDULED: return 1440;
            default:        return 0;
        }
    }
}
