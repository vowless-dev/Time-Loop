package com.vltno.timeloop;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

public enum MaxLoopTypes implements StringRepresentable {
    END_LOOP,
    REMOVE_OLD;

    @Override
    public @NotNull String getSerializedName() {
        return name().toLowerCase().replace("_", " ");
    }
}
