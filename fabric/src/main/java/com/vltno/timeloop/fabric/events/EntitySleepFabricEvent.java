package com.vltno.timeloop.fabric.events;

import com.vltno.timeloop.events.EntitySleepEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.BlockPos;

public class EntitySleepFabricEvent {
    public static void onStopSleeping(LivingEntity entity, BlockPos sleepingPos) {
        EntitySleepEvent.onStopSleeping(entity);
    }
}
