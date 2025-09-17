package com.vltno.timeloop.events;

import com.vltno.timeloop.LoopTypes;
import com.vltno.timeloop.TimeLoop;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.util.Arrays;

public class PlayConnectionEvent {
    public static void onJoin(ServerGamePacketListenerImpl handler, MinecraftServer server) {
        ServerPlayer player = handler.player;
        String playerName = player.getName().getString();
        HolderLookup.Provider provider = server.registryAccess();

        TimeLoop.loopSceneManager.addPlayer(Arrays.asList(playerName, null, null, player.position().toString(), new CompoundTag().toString()));

        CompoundTag invTag = TimeLoop.saveFullInventory(player, provider);
        TimeLoop.loopSceneManager.getRecordingPlayer(playerName).setInventoryTag(invTag);
        
        if (TimeLoop.loopBossBar != null) {
            TimeLoop.loopBossBar.addPlayer(player);
        }

        TimeLoop.executeCommand(String.format("mocap scenes add %s", TimeLoop.loopSceneManager.getPlayerSceneName(playerName)));

        if (TimeLoop.config != null && TimeLoop.config.firstStart) {
            TimeLoop.config.firstStart = false;
            TimeLoop.config.save();

            TimeLoop.LOOP_LOGGER.info("First start detected, sending message to op(s).");

            Component modrinthLink = Component.literal("https://modrinth.com/mod/timeloop")
                    .withStyle(Style.EMPTY
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://modrinth.com/mod/timeloop"))
                            .withColor(ChatFormatting.BLUE)
                            .withUnderlined(true)
                    );

            Component discordLink = Component.literal("https://discord.gg/nzDETZhqur")
                    .withStyle(Style.EMPTY
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://discord.gg/nzDETZhqur"))
                            .withColor(ChatFormatting.BLUE)
                            .withUnderlined(true)
                    );

            // Check if player is OP and send message directly
            if (server.getPlayerList().isOp(player.getGameProfile())) {

            }
        }

        if (TimeLoop.isLooping) {
            TimeLoop.LOOP_LOGGER.info("Starting recording for newly joined player: {}", playerName);
            TimeLoop.executeCommand(String.format("mocap recording start %s", playerName));
            
            if (TimeLoop.showLoopInfo && TimeLoop.loopBossBar != null) {
                
                boolean shouldBeVisible = TimeLoop.loopType != null && (TimeLoop.loopType.equals(LoopTypes.TICKS) || TimeLoop.loopType.equals(LoopTypes.TIME_OF_DAY));
                TimeLoop.loopBossBar.visible(shouldBeVisible);
            }
        }
    }

    public static void onDisconnect(ServerGamePacketListenerImpl handler) {
        ServerPlayer player = handler.player;
        String playerName = player.getName().getString();

        TimeLoop.loopSceneManager.removePlayer(playerName);
        if (TimeLoop.loopBossBar != null) {
            TimeLoop.loopBossBar.removePlayer(player);
        }
        if (TimeLoop.isLooping) {
            TimeLoop.saveRecordings();
            TimeLoop.loopSceneManager.saveRecordingPlayers();
        }
    }
}