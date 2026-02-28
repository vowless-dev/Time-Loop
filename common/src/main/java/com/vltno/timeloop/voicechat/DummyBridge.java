package com.vltno.timeloop.voicechat;

import com.vltno.timeloop.PlayerData;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class DummyBridge implements Bridge {
    // Do nothing — SVC is not loaded

    @Override
    public void onStartPlayback(PlayerData playerData, List<Entity> entities) {}

    @Override
    public void onTick() {}

    @Override
    public void stopPlayback(String playerName) {}

    @Override
    public CompletableFuture<Void> saveAudio() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> loadAudio() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void shutdown() {}
}
