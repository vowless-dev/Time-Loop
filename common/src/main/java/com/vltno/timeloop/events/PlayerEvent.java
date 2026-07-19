package com.vltno.timeloop.events;

import com.vltno.timeloop.PlayerData;
import com.vltno.timeloop.types.LoopTypes;
import com.vltno.timeloop.TimeLoop;
import net.minecraft.server.level.ServerPlayer;

public class PlayerEvent {
    public static void afterRespawn(ServerPlayer player) {
        if (!TimeLoop.isLooping) return;
        if (TimeLoop.loopType != LoopTypes.DEATH) return;

        // Only trigger if the respawning player is actually being recorded
        String playerName = player.getName().getString();
        PlayerData data = TimeLoop.loopSceneManager.getRecordingPlayer(playerName);
        if (data == null || !data.getActive()) return;

        TimeLoop.LOOP_LOGGER.info("Recording player '{}' respawned, advancing loop.", playerName);
        TimeLoop.runLoopIteration();
    }

    public static void dimensionChange(ServerPlayer player) {
        if (!TimeLoop.isLooping) return;
        TimeLoop.handlePlayerDimensionChange(player);
    }
}
