package com.mapsyncer.util;

public record RegionMeta(long timestampSeconds, String hash) {

    public String format() {
        return timestampSeconds + ":" + hash;
    }
}
