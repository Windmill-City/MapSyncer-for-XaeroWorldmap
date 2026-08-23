package com.mapsyncer.util;

public record RegionMeta(long timestampSeconds) {

    public String format() {
        return String.valueOf(timestampSeconds);
    }
}
