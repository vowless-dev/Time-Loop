package com.vltno.timeloop.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.vltno.timeloop.LoopCommands;
import com.vltno.timeloop.LoopTypes;
import com.vltno.timeloop.TimeLoop;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.regex.Pattern;

public class TogglesCommands {
    public static void register(LiteralArgumentBuilder<CommandSourceStack> settingsCommandBuilder)
    {
        LiteralArgumentBuilder<CommandSourceStack> togglesNode = Commands.literal("toggles");

        togglesNode.then(toggleCommand(TimeLoop.trackTimeOfDay, "trackTimeOfDay", TogglesCommands::trackTimeOfDay));

        togglesNode.then(toggleCommand(TimeLoop.trackItems, "trackItems", TogglesCommands::trackItems));

        togglesNode.then(toggleCommand(TimeLoop.trackInventory, "trackItems", TogglesCommands::trackInventory));

        togglesNode.then(toggleCommand(TimeLoop.displayTimeInTicks, "displayTimeInTicks", TogglesCommands::displayTimeInTicks));

        togglesNode.then(toggleCommand(TimeLoop.showLoopInfo, "showLoopInfo", TogglesCommands::showLoopInfo));

        togglesNode.then(toggleCommand(TimeLoop.trackChat, "trackChat", TogglesCommands::trackChat));

        togglesNode.then(toggleCommand(TimeLoop.hurtLoopedPlayers, "hurtLoopedPlayers", TogglesCommands::hurtLoopedPlayers));

        settingsCommandBuilder.then(togglesNode);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> toggleCommand(boolean bool, String name, Command command) {
        String spacedName = Pattern.compile("(?<=[a-z])(?=[A-Z])").matcher(name).replaceAll(" ").toLowerCase();
        String finalName = spacedName.substring(0,1).toUpperCase() + spacedName.substring(1).toLowerCase();

        return (LiteralArgumentBuilder<CommandSourceStack>) Commands.literal(name)
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(finalName + " is set to: " + bool), false);
                    return 1;
                })
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(command));
    }

    private static int trackTimeOfDay(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean newTrackTimeOfDay = BoolArgumentType.getBool(context, "value");
        TimeLoop.trackTimeOfDay = newTrackTimeOfDay;
        TimeLoop.config.trackTimeOfDay = newTrackTimeOfDay;
        TimeLoop.config.save();

        source.sendSuccess(() -> Component.literal("Track time of day is set to: " + newTrackTimeOfDay), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Track time of day set to {}", newTrackTimeOfDay);
        return 1;
    }

    private static int trackItems(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean newTrackItems = BoolArgumentType.getBool(context, "value");
        TimeLoop.trackItems = newTrackItems;
        TimeLoop.config.trackItems = newTrackItems;
        TimeLoop.config.save();

        TimeLoop.updateEntitiesToTrack(newTrackItems);

        source.sendSuccess(() -> Component.literal("Track items is set to: " + newTrackItems), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Track items set to {}", newTrackItems);
        return 1;
    }

    private static int trackInventory(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean newTrackInventory = BoolArgumentType.getBool(context, "value");
        TimeLoop.trackInventory = newTrackInventory;
        TimeLoop.config.trackInventory = newTrackInventory;
        TimeLoop.config.save();

        TimeLoop.loopSceneManager.forEachRecordingPlayer(playerData -> {
            String playerName = playerData.getName();
            Player player = TimeLoop.server.getPlayerList().getPlayerByName(playerName);

            HolderLookup.Provider provider = TimeLoop.server.registryAccess();

            CompoundTag invTag = TimeLoop.saveFullInventory(player, provider);
            playerData.setInventoryTag(invTag);
        });

        source.sendSuccess(() -> Component.literal("Track inventory is set to: " + newTrackInventory), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Track inventory set to {}", newTrackInventory);
        return 1;
    }

    private static int displayTimeInTicks(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean newDisplayTimeInTicks = BoolArgumentType.getBool(context, "value");
        TimeLoop.displayTimeInTicks = newDisplayTimeInTicks;
        TimeLoop.config.displayTimeInTicks = newDisplayTimeInTicks;
        TimeLoop.config.save();

        source.sendSuccess(() -> Component.literal("Display time in ticks is set to: " + newDisplayTimeInTicks), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Display time in ticks set to {}", newDisplayTimeInTicks);
        return 1;
    }

    private static int showLoopInfo(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean newShowLoopInfo = BoolArgumentType.getBool(context, "value");
        TimeLoop.showLoopInfo = newShowLoopInfo;
        TimeLoop.config.showLoopInfo = newShowLoopInfo;
        TimeLoop.config.save();

        if (TimeLoop.loopBossBar != null) {
            if (newShowLoopInfo) {
                TimeLoop.loopBossBar.visible(TimeLoop.loopType != null && (TimeLoop.loopType.equals(LoopTypes.TICKS) || TimeLoop.loopType.equals(LoopTypes.TIME_OF_DAY)));
            } else {
                TimeLoop.loopBossBar.visible(false);
            }
        }

        source.sendSuccess(() -> Component.literal("Show loop info is set to: " + newShowLoopInfo), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Show loop info set to {}", newShowLoopInfo);
        return 1;
    }

    private static int trackChat(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean newTrackChat = BoolArgumentType.getBool(context, "value");
        TimeLoop.trackChat = newTrackChat;
        TimeLoop.config.trackChat = newTrackChat;
        TimeLoop.config.save();

        TimeLoop.executeCommand("mocap settings recording chat_recording " + newTrackChat);

        source.sendSuccess(() -> Component.literal("Track chat is set to: " + newTrackChat), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Track chat set to {}", newTrackChat);
        return 1;
    }

    private static int hurtLoopedPlayers(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean newHurtLoopedPlayers = BoolArgumentType.getBool(context, "value");
        TimeLoop.hurtLoopedPlayers = newHurtLoopedPlayers;
        TimeLoop.config.hurtLoopedPlayers = newHurtLoopedPlayers;
        TimeLoop.config.save();

        TimeLoop.executeCommand("mocap settings playback invulnerable_playback " + !newHurtLoopedPlayers);

        source.sendSuccess(() -> Component.literal("Hurt looped players is set to: " + newHurtLoopedPlayers), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Hurt looped players set to {}", newHurtLoopedPlayers);
        return 1;
    }
}