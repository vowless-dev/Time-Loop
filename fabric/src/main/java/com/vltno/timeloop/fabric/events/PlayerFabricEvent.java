package com.vltno.timeloop.fabric.events;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.vltno.timeloop.events.PlayerEvent;

public class PlayerFabricEvent {
    public static void afterRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
        PlayerEvent.afterRespawn();
    }

    public static void dimensionChange(ServerPlayer player, ServerLevel origin, ServerLevel destination) {
        PlayerEvent.dimensionChange(player);
    }
}
