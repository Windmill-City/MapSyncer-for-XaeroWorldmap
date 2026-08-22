package com.mapsyncer.util;

public record ClientMeta(long timestampSeconds, String hash) {

    public String format() {
        return timestampSeconds + ":" + hash;
    }
}
