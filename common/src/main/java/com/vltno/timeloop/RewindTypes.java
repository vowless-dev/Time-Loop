package com.vltno.timeloop;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

public enum RewindTypes implements StringRepresentable {
    NONE,
    START_POSITION,
    JOIN_POSITION,
    SPAWN_POSITION;

    @Override
    public @NotNull String getSerializedName() {
        return name();
    }
}