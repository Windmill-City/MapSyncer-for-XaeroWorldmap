package com.mapsyncer.util;

public final class PathMapping {

    public static String toServerFolderName(String dimId) {
        return dimId.replace(':', '$').replace('/', '%');
    }
}
