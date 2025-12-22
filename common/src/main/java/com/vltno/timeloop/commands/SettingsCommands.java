package com.vltno.timeloop.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.vltno.timeloop.*;
import com.vltno.timeloop.types.LoopTypes;
import com.vltno.timeloop.types.MaxLoopsTypes;
import com.vltno.timeloop.types.RewindTypes;
import com.vltno.timeloop.types.SkinTypes;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.function.Consumer;

public class SettingsCommands {
    public static void register(LiteralArgumentBuilder<CommandSourceStack> parentBuilder) {
        LiteralArgumentBuilder<CommandSourceStack> settingsNode = Commands.literal("settings")
                .requires(source -> source.hasPermission(2));

        var loopTypeNode = Commands.literal("loopType")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(getLoopTypeText()), false);
                    return 1;
                });

        for (LoopTypes loopType : LoopTypes.values()) {
            String commandName = loopType.getCommandName();

            switch (loopType) {
                case TICKS -> {
                    loopTypeNode.then(Commands.literal(commandName)
                        .then(Commands.argument(commandName, IntegerArgumentType.integer(20))
                                .executes(context -> loopType(context, loopType, commandName, (newTicks) -> {
                                    TimeLoop.loopLengthTicks = newTicks;
                                    TimeLoop.config.loopLengthTicks = newTicks;

                                    TimeLoop.ticksLeft = newTicks;
                                    TimeLoop.config.ticksLeft = newTicks;
                                }))));
                }

                case TIME_OF_DAY -> {
                    String argumentName = commandName.split("_")[0];
                    loopTypeNode.then(Commands.literal(commandName)
                        .then(Commands.argument(argumentName, IntegerArgumentType.integer(0, 24000))
                                .executes(context -> loopType(context, loopType, argumentName, (newTime) -> {
                                    TimeLoop.timeSetting = newTime;
                                    TimeLoop.config.timeSetting = newTime;
                                }))));
                }

                default -> {
                    loopTypeNode.then(Commands.literal(commandName)
                                .executes(context -> loopType(context, loopType, null, null)));
                }
            }
        }

        settingsNode.then(loopTypeNode);

        var maxLoopsNode = Commands.literal("maxLoops")
                .executes(context -> {
                    String message = "Max loops is set to: " + ((TimeLoop.maxLoops == 0) ? TimeLoop.maxLoopsType.getSerializedName() : TimeLoop.maxLoops + " [" + TimeLoop.maxLoopsType.getSerializedName() + "]");
                    context.getSource().sendSuccess(() -> Component.literal(message), false);
                    return 1;
                });

        for (MaxLoopsTypes maxLoopsType : MaxLoopsTypes.values()) {
            String commandName = maxLoopsType.getCommandName();

            switch (maxLoopsType) {
                case DISABLED -> maxLoopsNode.then(Commands.literal(commandName).executes(context -> maxLoops(context, maxLoopsType)));
                default -> maxLoopsNode.then(Commands.literal(commandName)
                        .then(Commands.argument("value", IntegerArgumentType.integer(1))
                            .executes(context -> maxLoops(context, maxLoopsType))));
            }
        }

        settingsNode.then(maxLoopsNode);

        settingsNode.then(Commands.literal("modifyPlayer")
                .then(Commands.argument("targetPlayer", StringArgumentType.string())
                        .suggests(((context, builder) -> CommandUtils.PlayerSuggestion(builder)))
                .then(Commands.argument("newName", StringArgumentType.string())
                .then(Commands.argument("newSkin", StringArgumentType.string())
                        .executes(SettingsCommands::modifyPlayer)))));
        
        settingsNode.then(Commands.literal("rewindType")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("Rewind type is set to: " + TimeLoop.rewindType), false);
                    return 1;
                })
                .then(Commands.argument("rewindType", StringArgumentType.word())
                        .suggests((context, builder) -> CommandUtils.EnumSuggestion(builder, RewindTypes.class))
                        .executes(SettingsCommands::rewindType)));
        
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

    private static int loopType(CommandContext<CommandSourceStack> context, LoopTypes loopType, @Nullable String argumentName, @Nullable Consumer<Integer> setter) {
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

    private static int maxLoops(CommandContext<CommandSourceStack> context, MaxLoopsTypes maxLoopsType) {
        CommandSourceStack source = context.getSource();
        if (maxLoopsType == MaxLoopsTypes.DISABLED) {
            TimeLoop.maxLoops = 0;
            TimeLoop.config.maxLoops = 0;
        } else {
            int maxLoops = IntegerArgumentType.getInteger(context, "value");

            TimeLoop.maxLoops = maxLoops;
            TimeLoop.config.maxLoops = maxLoops;
        }

        TimeLoop.maxLoopsType = maxLoopsType;
        TimeLoop.config.maxLoopsType = maxLoopsType;

        TimeLoop.config.save();

        String message = "Max loops is set to: " + ((TimeLoop.maxLoops == 0) ? TimeLoop.maxLoopsType.getSerializedName() : TimeLoop.maxLoops + " [" + TimeLoop.maxLoopsType.getSerializedName() + "]");
        source.sendSuccess(() -> Component.literal(message), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info(message);
        return 1;
    }

    private static int modifyPlayer(CommandContext<CommandSourceStack> context) {
        // TODO! - Add proper error messages!!!
        CommandSourceStack source = context.getSource();
        String targetPlayer = StringArgumentType.getString(context, "targetPlayer");
        String newName = StringArgumentType.getString(context, "newName").replace(" ", "").toLowerCase();

        Skin finalSkin = new Skin();
        String rawSkinValue = StringArgumentType.getString(context, "newSkin").replace(" ", "").toLowerCase();
        finalSkin.value = rawSkinValue;

        try {
            String testUrl = rawSkinValue.contains("https://") ? rawSkinValue : "https://" + rawSkinValue;

            URI uri = new URI(testUrl);
            String host = uri.getHost();

            if (host.equals("mineskin.org") || host.equals("minesk.in")) {
                finalSkin.value = testUrl.replaceFirst("^[a-zA-Z]+://", "");

                finalSkin.skinType = SkinTypes.MINESKIN;

                LoopCommands.LOOP_COMMANDS_LOGGER.info("Detected MineSkin URL {}", finalSkin.value);
            } else {
                LoopCommands.LOOP_COMMANDS_LOGGER.info("Not a valid MineSkin URL {}", testUrl);
            }
        } catch (URISyntaxException | NullPointerException e) {
            LoopCommands.LOOP_COMMANDS_LOGGER.info("Skin input is not a URL, treating as username: {}", rawSkinValue);
        }

        TimeLoop.modifyPlayerAttributes(targetPlayer, newName, finalSkin);
        source.sendSuccess(() -> Component.literal("Modified player " + targetPlayer), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Modified player {} with name {} and skin {}", targetPlayer, newName, finalSkin.value);
        return 1;
    }

    private static int rewindType(CommandContext<CommandSourceStack> context) {
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