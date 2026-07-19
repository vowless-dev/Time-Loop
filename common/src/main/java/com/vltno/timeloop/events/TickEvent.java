package com.vltno.timeloop.events;

import com.vltno.timeloop.TimeLoop;
import com.vltno.timeloop.compat.VoicechatCompat;

public class TickEvent {
    public static void onEndServerTick() {
        Runnable task;
        while ((task = TimeLoop.delayedTasks.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                TimeLoop.LOOP_LOGGER.error("Error executing delayed task", e);
            }
        }

        if (!TimeLoop.isLooping) return;

        // tickCounter and voice tick must ALWAYS increment, regardless of loop type.
        // Voice recording stamps frames with tickCounter, and voice playback uses it
        // to schedule speech regions. SLEEP/DEATH modes still need both.
        TimeLoop.tickCounter++;
        VoicechatCompat.onTick();

        // Loop-type-specific iteration triggers
        switch (TimeLoop.loopType) {
            case TIME_OF_DAY -> {
                if (TimeLoop.timeSetting <= TimeLoop.startTimeOfDay) {
                    TimeLoop.startTimeOfDay = 0;
                    TimeLoop.config.startTimeOfDay = 0;
                }

                long time = (TimeLoop.serverLevel.dayTime() > 24000
                        ? TimeLoop.serverLevel.dayTime() % 24000
                        : TimeLoop.serverLevel.dayTime());

                long timeLeft = (time > TimeLoop.timeSetting)
                        ? Math.abs(TimeLoop.serverLevel.dayTime() - (2 * TimeLoop.timeSetting))
                        : Math.abs(time - TimeLoop.timeSetting);

                TimeLoop.updateInfoBar((int) TimeLoop.timeSetting, (int) timeLeft);
                if (Math.abs(TimeLoop.timeSetting - timeLeft) >= TimeLoop.timeSetting) {
                    TimeLoop.tickCounter = 0;
                    TimeLoop.runLoopIteration();
                }
            }
            case TICKS -> {
                TimeLoop.ticksLeft = TimeLoop.loopLengthTicks - TimeLoop.tickCounter;

                TimeLoop.updateInfoBar(TimeLoop.loopLengthTicks, TimeLoop.ticksLeft);
                if (TimeLoop.tickCounter >= TimeLoop.loopLengthTicks) {
                    TimeLoop.tickCounter = 0;
                    TimeLoop.ticksLeft = TimeLoop.loopLengthTicks;
                    TimeLoop.runLoopIteration();
                }
            }
            // SLEEP, DEATH, MANUAL — iteration triggered externally, nothing to do here
            default -> {}
        }
    }
}
