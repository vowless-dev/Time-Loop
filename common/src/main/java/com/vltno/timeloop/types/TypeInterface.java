package com.vltno.timeloop.types;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

public interface TypeInterface extends StringRepresentable {
    default String getCommandName() {
        if (this instanceof Enum<?>) {
            return ((Enum<?>) this).name().toLowerCase();
        }
        return "";
    }

    @Override
    default @NotNull String getSerializedName() {
        if (this instanceof Enum<?>) {
            return ((Enum<?>) this).name().toLowerCase().replace("_", " ");
        }
        return "";
    }
}
