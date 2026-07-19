package com.vltno.timeloop.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.vltno.timeloop.*;
import com.vltno.timeloop.compat.VoicechatCompat;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.mt1006.mocap.api.v1.io.CommandOutput;
import net.mt1006.mocap.mocap.playing.playable.SceneFile;
import net.mt1006.mocap.mocap.files.SceneFiles;
import net.mt1006.mocap.mocap.playing.PlaybackManager;

import java.util.List;

public class BaseCommands {
    public static void register(LiteralArgumentBuilder<CommandSourceStack> parentBuilder) {

        parentBuilder.then(Commands.literal("start")
                .executes(BaseCommands::start)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)));
        
        parentBuilder.then(Commands.literal("skip")
                .executes(BaseCommands::skip)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)));
        
        parentBuilder.then(Commands.literal("stop")
                .executes(BaseCommands::stop)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)));

        parentBuilder.then(Commands.literal("reset")
                .executes(BaseCommands::reset)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)));

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

        List<String> trackedItems = CommandUtils.getTrackedItemsFromReflection();

        String tracking;
        int count = trackedItems.size();

        if (count == 0) {
            tracking = ""; // Nothing tracked
        } else if (count == 1) {
            // Only one thing is being tracked
            tracking = "Tracking " + trackedItems.get(0) + ".";
        } else {
            // Two or more are being tracked
            tracking = "Tracking " + CommandUtils.formatList(trackedItems) + ".";
        }

        String loopTypeName = TimeLoop.loopType.getSerializedName();

        String extras = "Looping on " + loopTypeName + ".\n"
                + ("Max loops is set to: " + ((TimeLoop.maxLoops == 0) ? TimeLoop.maxLoopsType.getSerializedName() + "\n" : TimeLoop.maxLoops + " [" + TimeLoop.maxLoopsType.getSerializedName() + "].\n"))
                + (tracking.isEmpty() ? "" : tracking) + "\n";

        String voice = "Simple Voice Chat: " + (TimeLoop.voiceChatLoaded ? "Installed" : "Not present") + ".\n"
                + "Voice Chat Interaction: " + (TimeLoop.voiceInteractionLoaded ? "Installed" : "Not present") + ".\n";

        String status = (TimeLoop.isLooping ?
                "Loop is running.\n" : "Loop is not running.\n") + "Iteration: " + TimeLoop.loopIteration + ".\n" + extras + voice;

        source.sendSuccess(() -> Component.literal(status), false);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Status requested: {}", status);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        TimeLoop.stopLoop();

        // Stop all mocap playbacks and clear + recreate scene files
        PlaybackManager.stopAll(CommandOutput.DUMMY, null);
        TimeLoop.loopSceneManager.forEachPlayerSceneName(playerSceneName -> {
            SceneFile sceneFile = SceneFile.get(CommandOutput.LOGS, playerSceneName);
            if (sceneFile != null && sceneFile.exists()) {
                sceneFile.clear(CommandOutput.LOGS);
            } else {
                SceneFiles.add(CommandOutput.LOGS, playerSceneName);
            }
        });

        // Stop voice playback and delete all voice audio (disk + memory)
        VoicechatCompat.stopPlayback(null);
        VoicechatCompat.deleteAllAudio();

        // Delete orphaned mocap recording files from disk
        TimeLoop.removeAllRecordings();

        TimeLoop.loopIteration = 0;
        TimeLoop.config.loopIteration = 0;
        TimeLoop.config.save();

        source.sendSuccess(() -> Component.literal("Loop reset!"), true);
        return 1;
    }
}