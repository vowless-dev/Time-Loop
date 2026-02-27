package com.vltno.timeloop.voicechat;

import com.vltno.timeloop.PlayerData;

import java.util.concurrent.CompletableFuture;

public interface Bridge {
    void onStartPlayback(PlayerData playerData);
    void onTick();
    void stopPlayback(String playerName);

    /** Save all voice data to disk. */
    CompletableFuture<Void> saveAudio();

    /** Load all voice data from disk into memory. */
    CompletableFuture<Void> loadAudio();

    /** Clean up resources (thread pools, etc.). Call on server stop. */
    void shutdown();
}
