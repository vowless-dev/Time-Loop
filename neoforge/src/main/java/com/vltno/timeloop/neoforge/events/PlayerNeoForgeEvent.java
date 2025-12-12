package com.vltno.timeloop.neoforge.events;

import com.vltno.timeloop.events.PlayerEvent;
import net.minecraft.server.level.ServerPlayer;

public class PlayerNeoForgeEvent {
    public static void afterRespawn() {
        PlayerEvent.afterRespawn();
    }

    public static void dimensionChange(ServerPlayer player) {
        PlayerEvent.dimensionChange(player);
    }
}
