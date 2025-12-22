package com.vltno.timeloop.events;


import com.vltno.timeloop.types.LoopTypes;
import com.vltno.timeloop.TimeLoop;
import net.minecraft.server.level.ServerPlayer;

public class PlayerEvent {
    public static void afterRespawn() {
        if (TimeLoop.loopType == LoopTypes.DEATH) {
            TimeLoop.LOOP_LOGGER.info("RESPAWNED");
            TimeLoop.runLoopIteration();
        }
    }

    public static void dimensionChange(ServerPlayer player) {
        if (!TimeLoop.isLooping) return;
        TimeLoop.LOOP_LOGGER.info("{} CHANGED DIMENSION", player.getName().getString());
        TimeLoop.handlePlayerDimensionChange(player);
    }
}
