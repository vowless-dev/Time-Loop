package com.vltno.timeloop.voicechat;

import com.vltno.timeloop.PlayerData;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface Bridge {
    /**
     * Starts voice playback for all previous iterations of the given player.
     *
     * @param playerData the player whose voice recordings should play
     * @param entities   pre-assigned mocap FakePlayer entities (one per scene
     *                   element), already spawned by {@code sceneFile.startPlayback()}.
     *                   The Nth entity corresponds to the Nth scene recording.
     *                   May be empty if no entities were found.
     */
    void onStartPlayback(PlayerData playerData, List<Entity> entities);
    void onTick();
    void stopPlayback(String playerName);

    /** Save all voice data to disk. */
    CompletableFuture<Void> saveAudio();

    /** Load all voice data from disk into memory. */
    CompletableFuture<Void> loadAudio();

    /** Clean up resources (thread pools, etc.). Call on server stop. */
    void shutdown();
}
