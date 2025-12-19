package com.vltno.timeloop.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.vltno.timeloop.LoopCommands;
import com.vltno.timeloop.LoopTypes;
import com.vltno.timeloop.RewindTypes;
import com.vltno.timeloop.TimeLoop;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.function.Consumer;

public class SettingsCommands {
    public static void register(LiteralArgumentBuilder<CommandSourceStack> parentBuilder) {
        LiteralArgumentBuilder<CommandSourceStack> settingsNode = Commands.literal("settings")
                .requires(source -> source.hasPermission(2));

        var setLoopTypeNode = Commands.literal("setLoopType")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(getLoopTypeText()), false);
                    return 1;
                });

        for (LoopTypes loopType : LoopTypes.values()) {
            String commandName = loopType.getCommandName();

            switch (loopType) {
                case TICKS -> {
                    setLoopTypeNode.then(Commands.literal(commandName)
                        .then(Commands.argument(commandName, IntegerArgumentType.integer(20))
                                .executes(context -> setLoopType(context, loopType, commandName, (newTicks) -> {
                                    TimeLoop.loopLengthTicks = newTicks;
                                    TimeLoop.config.loopLengthTicks = newTicks;

                                    TimeLoop.ticksLeft = newTicks;
                                    TimeLoop.config.ticksLeft = newTicks;
                                }))));
                }

                case TIME_OF_DAY -> {
                    String argumentName = commandName.split("_")[0];
                    setLoopTypeNode.then(Commands.literal(commandName)
                        .then(Commands.argument(argumentName, IntegerArgumentType.integer(0, 24000))
                                .executes(context -> setLoopType(context, loopType, argumentName, (newTime) -> {
                                    TimeLoop.timeSetting = newTime;
                                    TimeLoop.config.timeSetting = newTime;
                                }))));
                }

                default -> {
                    setLoopTypeNode.then(Commands.literal(commandName)
                                .executes(context -> setLoopType(context, loopType, null, null)));
                }
            }
        }

        settingsNode.then(setLoopTypeNode);

        settingsNode.then(Commands.literal("maxLoops")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("Max loops is set to: " + TimeLoop.config.maxLoops), false);
                    return 1;
                })
                .then(Commands.argument("value", IntegerArgumentType.integer(0))
                        .executes(SettingsCommands::maxLoops)));

        settingsNode.then(Commands.literal("modifyPlayer")
                .then(Commands.argument("targetPlayer", StringArgumentType.string())
                .then(Commands.argument("newName", StringArgumentType.string())
                .then(Commands.argument("newSkin", StringArgumentType.string())
                        .executes(SettingsCommands::modifyPlayer)))));
        
        settingsNode.then(Commands.literal("setRewindType")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("Rewind type is set to: " + TimeLoop.rewindType), false);
                    return 1;
                })
                .then(Commands.argument("rewindType", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(Arrays.stream(RewindTypes.values()).map(RewindTypes::getCommandName), builder))
                        .executes(SettingsCommands::setRewindType)));
        
        TogglesCommands.register(settingsNode);

        parentBuilder.then(settingsNode);
    }

    private static String getLoopTypeText() {
        String text = "Loop type is set to " + TimeLoop.config.loopType.getSerializedName();
        switch (TimeLoop.config.loopType) {
            case TICKS -> text += " [" + TimeLoop.config.loopLengthTicks + " ticks]";

            case TIME_OF_DAY -> text += " [" + TimeLoop.config.timeSetting + " time]";

            case MANUAL -> text += ". Use '/loop skip' to advance to the next iteration.";

            default -> text += ".";
        }

        return text;
    }

    private static int setLoopType(CommandContext<CommandSourceStack> context, LoopTypes loopType, @Nullable String argumentName, @Nullable Consumer<Integer> setter) {
        TimeLoop.loopType = loopType;
        TimeLoop.config.loopType = loopType;

        if (argumentName != null && setter != null) {
            try {
                int value = IntegerArgumentType.getInteger(context, argumentName);

                setter.accept(value);

            } catch (IllegalArgumentException e) {
                LoopCommands.LOOP_COMMANDS_LOGGER.error(e.getMessage());
                return 0;
            }
        }

        TimeLoop.config.save();

        context.getSource().sendSuccess(() -> Component.literal(getLoopTypeText()), false);
        LoopCommands.LOOP_COMMANDS_LOGGER.info(getLoopTypeText());
        return 1;
    }

    private static int maxLoops(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int maxLoops = IntegerArgumentType.getInteger(context, "value");

        TimeLoop.maxLoops = maxLoops;
        TimeLoop.config.maxLoops = maxLoops;

        TimeLoop.config.save();

        source.sendSuccess(() -> Component.literal("Max loops is set to: " + maxLoops), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Max loops set to {}", maxLoops);
        return 1;
    }

    private static int modifyPlayer(CommandContext<CommandSourceStack> context) {
        // TODO! - Add proper error messages!!!
        CommandSourceStack source = context.getSource();
        String targetPlayer = StringArgumentType.getString(context, "targetPlayer");
        String newName = StringArgumentType.getString(context, "newName").replace(" ", "").toLowerCase();
        String newSkin = StringArgumentType.getString(context, "newSkin").replace(" ", "").toLowerCase(); //TODO! - add url support for skins

        TimeLoop.modifyPlayerAttributes(targetPlayer, newName, newSkin);
        source.sendSuccess(() -> Component.literal("Modified player " + targetPlayer), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Modified player {} with name {} and skin {}", targetPlayer, newName, newSkin);
        return 1;
    }


    private static int setRewindType(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String rewindTypeStr = StringArgumentType.getString(context, "rewindType");
        try {
            RewindTypes newRewindType = RewindTypes.valueOf(rewindTypeStr.toUpperCase());

            TimeLoop.rewindType = newRewindType;
            TimeLoop.config.rewindType = newRewindType;

            TimeLoop.config.save();

            source.sendSuccess(() -> Component.literal("Rewind type is set to: " + newRewindType), true);
            LoopCommands.LOOP_COMMANDS_LOGGER.info("Rewind type set to {}", newRewindType);
            return 1;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Invalid rewind type: " + rewindTypeStr));
            return 0;
        }
    }
}