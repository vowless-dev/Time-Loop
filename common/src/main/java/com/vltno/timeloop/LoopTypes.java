package com.vltno.timeloop;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

public enum LoopTypes implements StringRepresentable {
    TICKS,
    TIME_OF_DAY,
    SLEEP,
    DEATH,
    MANUAL;

    @Override
    public @NotNull String getSerializedName() {
        return name().toLowerCase().replace("_", " ");
    }

    public @NotNull String getCommandName() {
        return name().toLowerCase();
    }
}