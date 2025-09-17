package com.vltno.timeloop.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.vltno.timeloop.LoopCommands;
import com.vltno.timeloop.LoopTypes;
import com.vltno.timeloop.TimeLoop;
import com.vltno.timeloop.TimeLoopConfig;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
// Assuming TimeLoop.serverWorld is now 'net.minecraft.server.level.ServerLevel'

public class BaseCommands {

    // Note: The parent builder needs to be dispatched by Commands.register(...)
    // typically in your mod initializer or a dedicated command registration event.
    public static void register(LiteralArgumentBuilder<CommandSourceStack> parentBuilder) {

        parentBuilder.then(Commands.literal("start")
                .executes(BaseCommands::start)
                .requires(source -> source.hasPermission(2)));
        
        parentBuilder.then(Commands.literal("skip")
                .executes(BaseCommands::skip)
                .requires(source -> source.hasPermission(2)));
        
        parentBuilder.then(Commands.literal("stop")
                .executes(BaseCommands::stop)
                .requires(source -> source.hasPermission(2)));

        parentBuilder.then(Commands.literal("reset")
                .executes(BaseCommands::reset)
                .requires(source -> source.hasPermission(2)));

        parentBuilder.then(Commands.literal("status")
                .executes(BaseCommands::status)); // No permission needed
    }

    private static int start(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!TimeLoop.isLooping) {
            
            if (TimeLoop.serverLevel == null) {
                source.sendFailure(Component.literal("Error: Server world not available yet."));
                return 0;
            }
            TimeLoop.startTimeOfDay = TimeLoop.serverLevel.getDayTime();
            TimeLoop.config.startTimeOfDay = TimeLoop.startTimeOfDay;
            TimeLoop.startLoop();
            
            source.sendSuccess(() -> Component.literal("Loop started!"), true);
            LoopCommands.LOOP_COMMANDS_LOGGER.info("Loop started");
            return 1;
        }
        source.sendFailure(Component.literal("Loop already running!"));
        return 0;
    }

    private static int skip(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (TimeLoop.isLooping) {
            if (TimeLoop.serverLevel == null) {
                source.sendFailure(Component.literal("Error: Server world not available yet."));
                return 0;
            }
            
            TimeLoop.runLoopIteration();
          
            source.sendSuccess(() -> Component.literal("Loop skipped!"), true);
            LoopCommands.LOOP_COMMANDS_LOGGER.info("Loop skipped");
            return 1;
        }
        source.sendFailure(Component.literal("Loop not running"));
        return 0;
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (TimeLoop.isLooping) {
            TimeLoop.stopLoop();
            source.sendSuccess(() -> Component.literal("Loop stopped"), true);
            LoopCommands.LOOP_COMMANDS_LOGGER.info("Loop stopped");
            return 1;
        } else {
            source.sendFailure(Component.literal("Loop not running"));
        }
        return 0;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        String status = getString();
        
        source.sendSuccess(() -> Component.literal(status), false); 
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Status requested: {}", status);
        return 1;
    }

    private static @NotNull String getString() {
        String loopTypeName = (TimeLoop.loopType != null) ? TimeLoop.loopType.name() : "UNKNOWN";
        String extras = " Looping on " + loopTypeName + "." + (TimeLoop.isLooping && TimeLoop.loopType == LoopTypes.TICKS ? " Ticks Left: " + TimeLoop.ticksLeft : "") + (TimeLoop.trackItems ? " Tracking items." : "");
        return TimeLoop.isLooping ?
                "Loop is active. Current iteration: " + TimeLoop.loopIteration + extras :
                "Loop is inactive. Last iteration: " + TimeLoop.loopIteration + extras;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        TimeLoop.stopLoop();

        // Reset logic
        TimeLoopConfig baseConfig = new TimeLoopConfig();
        
        TimeLoop.startTimeOfDay = baseConfig.startTimeOfDay;
        TimeLoop.config.startTimeOfDay = baseConfig.startTimeOfDay;

        TimeLoop.timeSetting = baseConfig.timeSetting;
        TimeLoop.config.timeSetting = baseConfig.timeSetting;

        TimeLoop.ticksLeft = TimeLoop.loopLengthTicks;
        TimeLoop.config.ticksLeft = TimeLoop.config.loopLengthTicks;

        TimeLoop.trackItems = baseConfig.trackItems;
        TimeLoop.config.trackItems = baseConfig.trackItems;

        TimeLoop.loopType = baseConfig.loopType;
        TimeLoop.config.loopType = baseConfig.loopType;

        TimeLoop.displayTimeInTicks = baseConfig.displayTimeInTicks;
        TimeLoop.config.displayTimeInTicks = baseConfig.displayTimeInTicks;
        
        TimeLoop.executeCommand("mocap playback stop_all");
        TimeLoop.loopSceneManager.forEachPlayerSceneName(playerSceneName -> {
            TimeLoop.executeCommand(String.format("mocap scenes remove %s", playerSceneName));
            TimeLoop.executeCommand(String.format("mocap scenes add %s", playerSceneName));
        });

        TimeLoop.loopIteration = 0;
        TimeLoop.config.loopIteration = 0;
        TimeLoop.config.save();

        source.sendSuccess(() -> Component.literal("Loop reset!"), true);
        return 1;
    }
}