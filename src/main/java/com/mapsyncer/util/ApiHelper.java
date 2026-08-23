package com.mapsyncer.util;

import com.mapsyncer.mca.DimensionInfo;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

public final class ApiHelper {

    public static String getDimId(ResourceKey<Level> key) {
        return key.location().toString();
    }

    public static DimensionInfo fromDimensionType(DimensionType dimensionType) {
        return new DimensionInfo(
                dimensionType.hasSkyLight(),
                dimensionType.hasCeiling(),
                dimensionType.minY(),
                dimensionType.height(),
                dimensionType.logicalHeight());
    }

    public static Predicate<CommandSourceStack> admin() {
        return source -> source.hasPermission(4);
    }
}
