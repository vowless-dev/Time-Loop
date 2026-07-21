package com.vltno.timeloop.commands;

import com.mojang.brigadier.arguments.BoolArgumentType;
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

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

public class SettingsCommands {
    public static void register(LiteralArgumentBuilder<CommandSourceStack> parentBuilder) {
        LiteralArgumentBuilder<CommandSourceStack> settingsNode = Commands.literal("settings");

        //region loopType

        var loopTypeNode = Commands.literal("loopType")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(getLoopTypeText()), false);
                    return 1;
                });

        for (LoopTypes loopType : LoopTypes.values()) {
            String commandName = loopType.getCommandName();

            switch (loopType) {
                case DURATION -> {
                    loopTypeNode.then(Commands.literal(commandName)
                        .then(Commands.argument(commandName, StringArgumentType.word())
                                .suggests(CommandUtils::TimeSuggestion)
                                .executes(context -> loopTypeDuration(context, loopType, commandName))));
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
        //endregion

        //region maxLoops
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
        //endregion

        //region modifyPlayer
        var targetPlayerArg = Commands.argument("targetPlayer", StringArgumentType.string())
                .suggests((context, builder) -> CommandUtils.PlayerSuggestion(builder));

        var newNameArg = Commands.argument("newName", StringArgumentType.string())
                .executes(context -> modifyPlayer(context, SkinTypes.NAME));

        for (SkinTypes skinType : SkinTypes.values()) {
            String commandName = skinType.getCommandName();

            switch (skinType) {
                default -> {
                    newNameArg.then(Commands.literal(commandName)
                            .then(Commands.argument("newSkin", StringArgumentType.string())
                                    .executes(context -> modifyPlayer(context, skinType))));
                }
                case FILE -> {
                    newNameArg.then(Commands.literal(commandName)
                            .then(Commands.argument("newSkin", StringArgumentType.greedyString())
                                    .suggests(CommandUtils::SkinSuggestion)
                                    .executes(context -> modifyPlayer(context, skinType))));
                }
            }
        }

        var modifyPlayerNode = Commands.literal("modifyPlayer")
                .then(targetPlayerArg.then(newNameArg));

        settingsNode.then(modifyPlayerNode);

        //endregion
        
        settingsNode.then(Commands.literal("rewindType")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("Rewind type is set to: " + TimeLoop.rewindType), false);
                    return 1;
                })
                .then(Commands.argument("rewindType", StringArgumentType.word())
                        .suggests((context, builder) -> CommandUtils.EnumSuggestion(builder, RewindTypes.class))
                        .executes(SettingsCommands::rewindType)));
        
        //region voice
        registerVoiceCommands(settingsNode);
        //endregion

        TogglesCommands.register(settingsNode);

        parentBuilder.then(settingsNode);
    }

    private static String getLoopTypeText() {
        String text = "Loop type is set to " + TimeLoop.config.loopType.getSerializedName();
        switch (TimeLoop.config.loopType) {
            case DURATION -> text += " [" + TimeLoop.config.loopLengthTicks + " ticks]";

            case TIME_OF_DAY -> text += " [" + TimeLoop.config.timeSetting + " time]";

            case MANUAL -> text += ". Use '/loop skip' to advance to the next iteration.";

            default -> text += ".";
        }

        return text;
    }

    private static int loopTypeDuration(CommandContext<CommandSourceStack> context, LoopTypes loopType, String argumentName) {
        String durationStr = StringArgumentType.getString(context, argumentName);
        int newTicks;
        try {
            newTicks = CommandUtils.parseDurationToTicks(durationStr);
            if (newTicks < 20) {
                context.getSource().sendFailure(Component.literal("Duration must be at least 1 second (20 ticks)."));
                return 0;
            }
        } catch (IllegalArgumentException e) {
            context.getSource().sendFailure(Component.literal("Invalid duration format: " + durationStr));
            return 0;
        }

        TimeLoop.loopType = loopType;
        TimeLoop.config.loopType = loopType;

        TimeLoop.loopLengthTicks = newTicks;
        TimeLoop.config.loopLengthTicks = newTicks;

        TimeLoop.ticksLeft = newTicks;
        TimeLoop.config.ticksLeft = newTicks;

        TimeLoop.config.save();

        context.getSource().sendSuccess(() -> Component.literal(getLoopTypeText()), false);
        LoopCommands.LOOP_COMMANDS_LOGGER.info(getLoopTypeText());
        return 1;
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

    private static int modifyPlayer(CommandContext<CommandSourceStack> context, SkinTypes skinType) {
        CommandSourceStack source = context.getSource();
        String targetPlayer = StringArgumentType.getString(context, "targetPlayer");

        // Validate the target player exists in the loop
        PlayerData targetData = TimeLoop.loopSceneManager.getRecordingPlayer(targetPlayer);
        if (targetData == null) {
            source.sendFailure(Component.literal("Player '" + targetPlayer + "' is not a recording player."));
            return 0;
        }

        String newName = StringArgumentType.getString(context, "newName").replace(" ", "").toLowerCase();
        if (newName.isEmpty()) {
            source.sendFailure(Component.literal("Player name cannot be empty."));
            return 0;
        }

        Skin finalSkin = new Skin();
        String rawSkinValue;
        try {
            rawSkinValue = StringArgumentType.getString(context, "newSkin").replace(" ", "").toLowerCase();
        } catch (Exception e) {
            rawSkinValue = newName;
        }

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
                String[] filetypes = {".jpg", ".jpeg", ".png"};
                boolean foundFile = false;

                for (String filetype : filetypes) {
                    String filename;
                    String finalRawSkinValue = rawSkinValue;
                    if (!Arrays.stream(filetypes).anyMatch(f -> Objects.equals(f, finalRawSkinValue))) {
                        filename = rawSkinValue + filetype;
                    } else {
                        filename = rawSkinValue;
                    }
                    File skinFile = new File(TimeLoop.worldFolder.resolve("mocap_files").resolve("skins").resolve(filename).toString());

                    if (skinFile.isFile()) {
                        LoopCommands.LOOP_COMMANDS_LOGGER.info("Detected skin at {}", skinFile.getPath());
                        finalSkin.value = rawSkinValue;
                        finalSkin.skinType = SkinTypes.FILE;
                        foundFile = true;
                        break;
                    }
                }

                if (!foundFile) {
                    source.sendFailure(Component.literal("Skin '" + rawSkinValue + "' is not a valid MineSkin URL or skin file."));
                    return 0;
                }
            }
        } catch (URISyntaxException | NullPointerException e) {
            // Not a URL — treat as player name skin (default)
            LoopCommands.LOOP_COMMANDS_LOGGER.debug("Skin input '{}' is not a URL, using as player name", rawSkinValue);
        }

        TimeLoop.modifyPlayerAttributes(targetPlayer, newName, finalSkin);
        source.sendSuccess(() -> Component.literal("Modified player " + targetPlayer + " -> '" + newName + "'"), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Modified player {} with name {} and skin {}", targetPlayer, newName, finalSkin.value);
        return 1;
    }

    /**
     * Registers the {@code /loop settings voice} category and its subcommands.
     * <p>
     * The category requires Simple Voice Chat to be installed.
     * Individual subcommands may have additional requirements (e.g.
     * voiceInteraction requires voicechat-interaction).
     */
    private static void registerVoiceCommands(LiteralArgumentBuilder<CommandSourceStack> settingsNode) {
        if (!TimeLoop.voiceChatLoaded) return;

        LiteralArgumentBuilder<CommandSourceStack> voiceNode = Commands.literal("voice");

        // trackVoice — enable/disable voice chat recording
        voiceNode.then(Commands.literal("trackVoice")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Track voice is set to: " + TimeLoop.trackVoice), false);
                    return 1;
                })
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(SettingsCommands::trackVoice)));

        // togglePlayerVoiceIcon — always available when SVC is loaded
        voiceNode.then(Commands.literal("togglePlayerVoiceIcon")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Player voice icon is set to: " + TimeLoop.showPlayerVoiceIcon), false);
                    return 1;
                })
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(SettingsCommands::togglePlayerVoiceIcon)));

        // voiceInteraction toggle — requires voicechat-interaction
        if (TimeLoop.voiceInteractionLoaded) {
            voiceNode.then(Commands.literal("voiceInteraction")
                    .executes(context -> {
                        context.getSource().sendSuccess(() -> Component.literal(
                                "Voice interaction is set to: " + TimeLoop.voiceInteractionEnabled), false);
                        return 1;
                    })
                    .then(Commands.argument("value", BoolArgumentType.bool())
                            .executes(SettingsCommands::voiceInteraction)));
        }

        settingsNode.then(voiceNode);
    }

    private static int trackVoice(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean newValue = BoolArgumentType.getBool(context, "value");
        TimeLoop.trackVoice = newValue;
        TimeLoop.config.trackVoice = newValue;
        TimeLoop.config.save();

        source.sendSuccess(() -> Component.literal("Track voice is set to: " + newValue), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Track voice set to {}", newValue);
        return 1;
    }

    private static int togglePlayerVoiceIcon(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean newValue = BoolArgumentType.getBool(context, "value");
        TimeLoop.showPlayerVoiceIcon = newValue;
        TimeLoop.config.showPlayerVoiceIcon = newValue;
        TimeLoop.config.save();

        source.sendSuccess(() -> Component.literal("Player voice icon is set to: " + newValue), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Player voice icon set to {}", newValue);
        return 1;
    }

    private static int voiceInteraction(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean newValue = BoolArgumentType.getBool(context, "value");
        TimeLoop.voiceInteractionEnabled = newValue;
        TimeLoop.config.voiceInteractionEnabled = newValue;
        TimeLoop.config.save();

        source.sendSuccess(() -> Component.literal("Voice interaction is set to: " + newValue), true);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Voice interaction set to {}", newValue);
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