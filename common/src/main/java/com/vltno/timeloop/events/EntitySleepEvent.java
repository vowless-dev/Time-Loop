package com.vltno.timeloop.events;

import com.vltno.timeloop.PlayerData;
import com.vltno.timeloop.types.LoopTypes;
import com.vltno.timeloop.TimeLoop;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

public class EntitySleepEvent {
    public static void onStopSleeping(LivingEntity entity) {
        if (entity.level().isClientSide()) return;
        if (!TimeLoop.isLooping) return;
        if (TimeLoop.loopType != LoopTypes.SLEEP) return;
        if (entity.getType() != EntityType.PLAYER) return;

        // Only trigger if the sleeping player is actually being recorded
        String playerName = entity.getName().getString();
        PlayerData data = TimeLoop.loopSceneManager.getRecordingPlayer(playerName);
        if (data == null || !data.getActive()) return;

        TimeLoop.LOOP_LOGGER.info("Recording player '{}' slept, advancing loop.", playerName);
        TimeLoop.runLoopIteration();
    }
}