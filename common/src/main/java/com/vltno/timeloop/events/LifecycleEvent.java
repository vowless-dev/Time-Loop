package com.vltno.timeloop.events;

import com.vltno.timeloop.LoopSceneManager;
import com.vltno.timeloop.TimeLoopConfig;
import net.minecraft.server.MinecraftServer;
import com.vltno.timeloop.TimeLoop;
import net.minecraft.world.level.storage.LevelResource;

public class LifecycleEvent {
    public static void onServerStart(MinecraftServer server)
    {
        // Load configuration from the config folder
        TimeLoop.worldFolder = server.getWorldPath(LevelResource.ROOT);
        TimeLoop.config = TimeLoopConfig.load(TimeLoop.worldFolder);

        // Loop scene manager
        TimeLoop.loopSceneManager = new LoopSceneManager(TimeLoop.config);

        TimeLoop.loopIteration = TimeLoop.config.loopIteration;
        TimeLoop.loopLengthTicks = TimeLoop.config.loopLengthTicks;
        TimeLoop.isLooping = TimeLoop.config.isLooping;
        TimeLoop.startTimeOfDay = TimeLoop.config.startTimeOfDay;
        TimeLoop.timeSetting = TimeLoop.config.timeSetting;
        TimeLoop.trackTimeOfDay = TimeLoop.config.trackTimeOfDay;
        TimeLoop.tickCounter = TimeLoop.config.tickCounter;
        TimeLoop.ticksLeft = TimeLoop.config.ticksLeft;

        TimeLoop.showLoopInfo = TimeLoop.config.showLoopInfo;
        TimeLoop.displayTimeInTicks = TimeLoop.config.displayTimeInTicks;
        TimeLoop.trackItems = TimeLoop.config.trackItems;
        TimeLoop.loopType = TimeLoop.config.loopType;
        TimeLoop.trackChat = TimeLoop.config.trackChat;
        TimeLoop.hurtLoopedPlayers = TimeLoop.config.hurtLoopedPlayers;
        TimeLoop.rewindType = TimeLoop.config.rewindType;
        TimeLoop.trackInventory = TimeLoop.config.trackInventory;

        TimeLoop.loopSceneManager.setRecordingPlayers(TimeLoop.config.recordingPlayers);

        TimeLoop.server = server;
        
        TimeLoop.serverLevel = server.overworld();
        
        if (TimeLoop.loopBossBar != null) {
            TimeLoop.loopBossBar.visible(false);
        }
        
        TimeLoop.executeCommand("mocap settings advanced experimental_release_warning false");
        TimeLoop.executeCommand("mocap settings playback start_as_recorded true");
        TimeLoop.executeCommand("mocap settings recording assign_player_name true");
        TimeLoop.executeCommand("mocap settings recording start_instantly true");
        TimeLoop.executeCommand("mocap settings recording on_death continue_synced");
        TimeLoop.executeCommand("mocap settings recording on_change_dimension split_recording");
        TimeLoop.executeCommand("mocap settings recording chat_recording " + TimeLoop.trackChat);
        TimeLoop.executeCommand("mocap settings playback invulnerable_playback " + !TimeLoop.hurtLoopedPlayers);
        TimeLoop.executeCommand("mocap settings recording entity_tracking_distance 1");

        TimeLoop.updateEntitiesToTrack(TimeLoop.trackItems);

        try {
            TimeLoop.loopSceneManager.forEachPlayerSceneName(playerSceneName -> TimeLoop.executeCommand(String.format("mocap scenes add %s", playerSceneName)));
        } catch (Exception e) {
            TimeLoop.LOOP_LOGGER.error("Failed to add player scenes to mocap scenes: {}", e.getMessage(), e);
        }
    }

    public static void onServerStopping()
    {
        if (TimeLoop.isLooping) {
            TimeLoop.stopLoop(true);
            TimeLoop.config.isLooping = true;
            TimeLoop.config.tickCounter = TimeLoop.tickCounter;
            TimeLoop.config.ticksLeft = TimeLoop.ticksLeft;
            
            if (TimeLoop.worldFolder != null) {
                TimeLoop.config.save();
            }
        } else {
            // Ensure config is saved even if not looping, might have pending changes
            if(TimeLoop.worldFolder != null) {
                TimeLoop.config.isLooping = false; // Ensure it saves as not looping
                TimeLoop.config.save();
            }
        }
    }
}