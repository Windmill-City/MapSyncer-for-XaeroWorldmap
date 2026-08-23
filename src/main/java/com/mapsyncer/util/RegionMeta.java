package com.mapsyncer.util;

public record RegionMeta(long timestampMillis) {

    public String format() {
        return String.valueOf(timestampMillis);
    }
}
