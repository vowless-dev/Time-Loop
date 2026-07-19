package com.vltno.timeloop.neoforge.events;

import com.vltno.timeloop.events.PlayerEvent;
import net.minecraft.server.level.ServerPlayer;

public class PlayerNeoForgeEvent {
    public static void afterRespawn(ServerPlayer player) {
        PlayerEvent.afterRespawn(player);
    }

    public static void dimensionChange(ServerPlayer player) {
        PlayerEvent.dimensionChange(player);
    }
}
