package com.vltno.timeloop.compat;

import com.vltno.timeloop.PlayerData;
import com.vltno.timeloop.TimeLoop;
import net.minecraft.world.entity.Entity;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A safe facade for Voicechat API interactions. 
 * Prevents java.lang.NoClassDefFoundError by ensuring no third-party classes 
 * are referenced in method signatures or fields.
 */
public final class VoicechatCompat {
    
    private VoicechatCompat() {}

    public static void onStartPlayback(PlayerData data, List<Entity> entities, List<String> sceneElementNames) {
        if (!TimeLoop.voiceChatLoaded) return;
        VoicechatImpl.onStartPlayback(data, entities, sceneElementNames);
    }

    public static void onTick() {
        if (!TimeLoop.voiceChatLoaded) return;
        VoicechatImpl.onTick();
    }

    public static List<Entity> findAllMocapEntities(String nickname) {
        if (!TimeLoop.voiceChatLoaded) return Collections.emptyList();
        return VoicechatImpl.findAllMocapEntities(nickname);
    }

    public static CompletableFuture<Void> saveAudio() {
        if (!TimeLoop.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        return VoicechatImpl.saveAudio();
    }

    public static CompletableFuture<Void> savePlayerAudio(PlayerData playerData) {
        if (!TimeLoop.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        return VoicechatImpl.savePlayerAudio(playerData);
    }

    public static CompletableFuture<Void> loadAudio() {
        if (!TimeLoop.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        return VoicechatImpl.loadAudio();
    }

    public static CompletableFuture<Void> deleteAllAudio() {
        if (!TimeLoop.voiceChatLoaded) return CompletableFuture.completedFuture(null);
        return VoicechatImpl.deleteAllAudio();
    }

    public static void shutdown() {
        if (!TimeLoop.voiceChatLoaded) return;
        VoicechatImpl.shutdown();
    }

    public static void stopPlayback(String player) {
        if (!TimeLoop.voiceChatLoaded) return;
        VoicechatImpl.stopPlayback(player);
    }
}
