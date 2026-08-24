package com.mapsyncer.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class PathHelper {

    public static String toServerFolderName(String dimId) {
        return dimId.replace(':', '$').replace('/', '%');
    }
    
    public static String getDimId(ResourceKey<Level> key) {
        return key.location().toString();
    }
}
