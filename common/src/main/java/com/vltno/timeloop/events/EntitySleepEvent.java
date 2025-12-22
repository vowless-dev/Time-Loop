package com.vltno.timeloop.events;

import com.vltno.timeloop.types.LoopTypes;
import com.vltno.timeloop.TimeLoop;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

public class EntitySleepEvent {
    public static void onStopSleeping(LivingEntity entity) {
        if (entity.level().isClientSide()) { return; }
        if ((entity.getType() == EntityType.PLAYER) && (TimeLoop.loopType == LoopTypes.SLEEP) ) {
            TimeLoop.LOOP_LOGGER.info("Player slept, looping.");
            TimeLoop.runLoopIteration();
        }
    }
}