package com.vltno.timeloop;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

public enum MaxLoopTypes implements StringRepresentable {
    STOP_LOOP,
    KEEP_NEWEST;

    @Override
    public @NotNull String getSerializedName() {
        return name().toLowerCase().replace("_", " ");
    }

    public @NotNull String getCommandName() {
        return name().toLowerCase();
    }
}
