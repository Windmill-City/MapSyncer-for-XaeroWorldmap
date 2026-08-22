package com.mapsyncer.util;

import net.minecraft.commands.CommandSourceStack;

import java.util.function.Predicate;

public final class CommandPermissionHelper {

    private CommandPermissionHelper() {}

    public static Predicate<CommandSourceStack> admin() {
        return source -> source.hasPermission(4);
    }
}
