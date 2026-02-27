package com.vltno.timeloop.events;

import com.vltno.timeloop.LoopSceneManager;
import com.vltno.timeloop.TimeLoopConfig;
import com.vltno.timeloop.compat.VoicechatInteractionCompat;
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
        TimeLoop.isLooping = TimeLoop.config.isLooping;
        TimeLoop.loopLengthTicks = TimeLoop.config.loopLengthTicks;
        TimeLoop.maxLoops = TimeLoop.config.maxLoops;
        TimeLoop.maxLoopsType = TimeLoop.config.maxLoopsType;
        TimeLoop.timeSetting = TimeLoop.config.timeSetting;
        TimeLoop.startTimeOfDay = TimeLoop.config.startTimeOfDay;
        TimeLoop.trackTimeOfDay = TimeLoop.config.trackTimeOfDay;
        TimeLoop.tickCounter = TimeLoop.config.tickCounter;
        TimeLoop.ticksLeft = TimeLoop.config.ticksLeft;

        TimeLoop.showLoopInfo = TimeLoop.config.showLoopInfo;
        TimeLoop.displayTimeInTicks = TimeLoop.config.displayTimeInTicks;
        TimeLoop.trackItems = TimeLoop.config.trackItems;
        TimeLoop.entityTrackingDistance = TimeLoop.config.entityTrackingDistance;
        TimeLoop.loopType = TimeLoop.config.loopType;
        TimeLoop.trackChat = TimeLoop.config.trackChat;
        TimeLoop.hurtLoopedPlayers = TimeLoop.config.hurtLoopedPlayers;
        TimeLoop.rewindType = TimeLoop.config.rewindType;
        TimeLoop.trackInventory = TimeLoop.config.trackInventory;

        // Voice interaction settings
        TimeLoop.voiceInteractionEnabled = TimeLoop.config.voiceInteractionEnabled;
        TimeLoop.voiceInteractionThresholdDb = TimeLoop.config.voiceInteractionThresholdDb;
        TimeLoop.voiceInteractionCooldownTicks = TimeLoop.config.voiceInteractionCooldownTicks;
        TimeLoop.voiceAudioDistance = TimeLoop.config.voiceAudioDistance;

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

        // updateEntitiesToTrack sets both the entity list AND the tracking distance
        TimeLoop.updateEntitiesToTrack(TimeLoop.trackItems);

        try {
            TimeLoop.loopSceneManager.forEachPlayerSceneName(playerSceneName -> TimeLoop.executeCommand(String.format("mocap scenes add %s", playerSceneName)));
        } catch (Exception e) {
            TimeLoop.LOOP_LOGGER.error("Failed to add player scenes to mocap scenes: {}", e.getMessage(), e);
        }

        // Resolve the vcinteraction game event from the registry (if vcinteraction is installed).
        // This must happen after registries are frozen (i.e. during server start, not mod init).
        VoicechatInteractionCompat.resolveGameEvent();

        // Load saved voice audio from disk (async, populates PlayerData audio maps)
        TimeLoop.voiceBridge.loadAudio();
    }

    public static void onServerStopping()
    {
        if (TimeLoop.isLooping) {
            // Capture loop progress BEFORE stopLoop() resets them
            int savedTickCounter = TimeLoop.tickCounter;
            int savedTicksLeft = TimeLoop.ticksLeft;
            int savedIteration = TimeLoop.loopIteration;

            // stopLoop() saves recordings + voice audio, resets counters
            TimeLoop.stopLoop();

            // Restore the state so the loop resumes where it left off on next server start
            TimeLoop.config.isLooping = true;
            TimeLoop.config.tickCounter = savedTickCounter;
            TimeLoop.config.ticksLeft = savedTicksLeft;
            TimeLoop.config.loopIteration = savedIteration;

            if (TimeLoop.worldFolder != null) {
                TimeLoop.config.save();
            }
        } else {
            // Ensure config is saved even if not looping, might have pending changes
            if (TimeLoop.worldFolder != null) {
                TimeLoop.config.isLooping = false;
                TimeLoop.config.save();
            }
        }

        // Clean up voice interaction cooldowns
        VoicechatInteractionCompat.clearCooldowns();

        // Shut down voice bridge thread pools
        TimeLoop.voiceBridge.shutdown();
    }
}