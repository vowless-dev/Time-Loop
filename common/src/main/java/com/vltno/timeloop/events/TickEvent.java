package com.vltno.timeloop.events;

import com.vltno.timeloop.types.LoopTypes;
import com.vltno.timeloop.TimeLoop;

public class TickEvent {
    public static void onEndServerTick() {
        if (TimeLoop.loopType == LoopTypes.SLEEP || TimeLoop.loopType == LoopTypes.DEATH) { return; }

        if (TimeLoop.isLooping) {
            TimeLoop.tickCounter++;
            if (TimeLoop.loopType == LoopTypes.TIME_OF_DAY) {
                if (TimeLoop.timeSetting <= TimeLoop.startTimeOfDay) { // prevent stupid 1 tick loop bug
                    TimeLoop.startTimeOfDay = 0;
                    TimeLoop.config.startTimeOfDay = 0;}

                long time = (TimeLoop.serverLevel.dayTime() > 24000 ? TimeLoop.serverLevel.dayTime() % 24000 : TimeLoop.serverLevel.dayTime());

                long timeLeft = (time > TimeLoop.timeSetting) ? Math.abs(TimeLoop.serverLevel.dayTime() - (2 * TimeLoop.timeSetting)) : Math.abs(time - TimeLoop.timeSetting);

                TimeLoop.updateInfoBar((int)TimeLoop.timeSetting, (int)timeLeft);
                if (Math.abs(TimeLoop.timeSetting - timeLeft) >= TimeLoop.timeSetting) {
                    TimeLoop.tickCounter = 0;
                    TimeLoop.runLoopIteration();
                }
            }

            else if (TimeLoop.loopType == LoopTypes.TICKS) {
                TimeLoop.ticksLeft = TimeLoop.loopLengthTicks - TimeLoop.tickCounter;

                TimeLoop.updateInfoBar(TimeLoop.loopLengthTicks, TimeLoop.ticksLeft);
                if (TimeLoop.tickCounter >= TimeLoop.loopLengthTicks) {
                    TimeLoop.tickCounter = 0; // Reset counter
                    TimeLoop.ticksLeft = TimeLoop.loopLengthTicks; // Reset
                    TimeLoop.runLoopIteration();
                }
            }

            TimeLoop.voiceBridge.onTick();
        }
    }
}
