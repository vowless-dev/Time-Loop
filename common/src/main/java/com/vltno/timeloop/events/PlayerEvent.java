package com.vltno.timeloop.events;


import com.vltno.timeloop.LoopTypes;
import com.vltno.timeloop.TimeLoop;

public class PlayerEvent {
    public static void afterRespawn() {
        if (TimeLoop.loopType == LoopTypes.DEATH) {
            TimeLoop.LOOP_LOGGER.info("RESPAWNED!!!!");
            TimeLoop.runLoopIteration();
        }
    }
}
