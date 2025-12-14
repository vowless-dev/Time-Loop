package com.vltno.timeloop.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.vltno.timeloop.LoopCommands;
import com.vltno.timeloop.LoopTypes;
import com.vltno.timeloop.TimeLoop;
import com.vltno.timeloop.TimeLoopConfig;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.sql.Time;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static net.minecraft.network.chat.ComponentUtils.formatList;

public class BaseCommands {
    public static void register(LiteralArgumentBuilder<CommandSourceStack> parentBuilder) {

        parentBuilder.then(Commands.literal("start")
                .executes(BaseCommands::start)
                .requires(source -> source.hasPermission(2)));
        
        parentBuilder.then(Commands.literal("skip")
                .executes(BaseCommands::skip)
                .requires(source -> source.hasPermission(2)));
        
        parentBuilder.then(Commands.literal("stop")
                .executes(BaseCommands::stop)
                .requires(source -> source.hasPermission(2)));

        parentBuilder.then(Commands.literal("reset")
                .executes(BaseCommands::reset)
                .requires(source -> source.hasPermission(2)));

        parentBuilder.then(Commands.literal("status")
                .executes(BaseCommands::status)); // No permission needed
    }

    private static int start(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!TimeLoop.isLooping) {
            
            if (TimeLoop.serverLevel == null) {
                source.sendFailure(Component.literal("Error: Server world not available yet."));
                return 0;
            }
            TimeLoop.startTimeOfDay = TimeLoop.serverLevel.getDayTime();
            TimeLoop.config.startTimeOfDay = TimeLoop.startTimeOfDay;
            TimeLoop.startLoop();
            
            source.sendSuccess(() -> Component.literal("Loop started!"), true);
            LoopCommands.LOOP_COMMANDS_LOGGER.info("Loop started");
            return 1;
        }
        source.sendFailure(Component.literal("Loop already running!"));
        return 0;
    }

    private static int skip(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (TimeLoop.isLooping) {
            TimeLoop.runLoopIteration();
          
            source.sendSuccess(() -> Component.literal("Loop skipped!"), true);
            LoopCommands.LOOP_COMMANDS_LOGGER.info("Loop skipped");
            return 1;
        }
        source.sendFailure(Component.literal("Loop not running"));
        return 0;
    }

    private static int stop(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (TimeLoop.isLooping) {
            TimeLoop.stopLoop();
            source.sendSuccess(() -> Component.literal("Loop stopped"), true);
            LoopCommands.LOOP_COMMANDS_LOGGER.info("Loop stopped");
            return 1;
        } else {
            source.sendFailure(Component.literal("Loop not running"));
        }
        return 0;
    }

    private static List<String> getTrackedItemsFromReflection() {
        List<String> trackedItems = new ArrayList<>();

        Class<?> timeLoopClass = TimeLoop.class;

        for (Field field : timeLoopClass.getDeclaredFields()) {
            if (field.getName().startsWith("track") && field.getType() == boolean.class) {
                try {
                    // In case fields aren't public
                    field.setAccessible(true);

                    if (field.getBoolean(null)) {
                        String rawName = field.getName();

                        // Convert "trackItems" to "Items"
                        String baseName = rawName.substring("track".length());

                        // Convert camelCase ("Items") to space-separated ("Items") and then lowercase ("items")
                        String readableName = Pattern.compile("(?<=[a-z])(?=[A-Z])").matcher(baseName).replaceAll(" ").toLowerCase();
                        trackedItems.add(readableName);
                    }
                } catch (IllegalAccessException e) {
                    LoopCommands.LOOP_COMMANDS_LOGGER.error("Failed to access reflection field: " + field.getName(), e);
                }
            }
        }
        return trackedItems;
    }

    private static @NotNull String formatList(List<String> items) {
        if (items.size() == 0) return "";
        if (items.size() == 1) return items.get(0);
        if (items.size() == 2) return items.get(0) + " and " + items.get(1);

        // For three or more items: use commas, and "and" for the last item
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                if (i == items.size() - 1) {
                    sb.append(", and ");
                } else {
                    sb.append(", ");
                }
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        List<String> trackedItems = getTrackedItemsFromReflection();

        String tracking;
        int count = trackedItems.size();

        if (count == 0) {
            tracking = ""; // Nothing tracked
        } else if (count == 1) {
            // Only one item is on: "Tracking items."
            tracking = "Tracking " + trackedItems.get(0) + ".";
        } else {
            // Two or more items are on
            tracking = "Tracking " + formatList(trackedItems) + ".";
        }

        String loopTypeName = TimeLoop.loopType.getSerializedName().toLowerCase().replace("_", "");

        String extras = "Looping on " + loopTypeName + "."
                + (TimeLoop.isLooping && TimeLoop.loopType == LoopTypes.TICKS ? " Ticks Left: " + TimeLoop.ticksLeft : "")
                + (tracking.isEmpty() ? "" : " " + tracking);

        String status = TimeLoop.isLooping ?
                "Loop is running. Iteration: " + TimeLoop.loopIteration + ". " + extras :
                "Loop is not running. Iteration: " + TimeLoop.loopIteration + ". " + extras;

        source.sendSuccess(() -> Component.literal(status), false);
        LoopCommands.LOOP_COMMANDS_LOGGER.info("Status requested: {}", status);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        TimeLoop.stopLoop();

        // Reset logic
        TimeLoopConfig baseConfig = new TimeLoopConfig();
        
        TimeLoop.startTimeOfDay = baseConfig.startTimeOfDay;
        TimeLoop.config.startTimeOfDay = baseConfig.startTimeOfDay;

        TimeLoop.timeSetting = baseConfig.timeSetting;
        TimeLoop.config.timeSetting = baseConfig.timeSetting;

        TimeLoop.ticksLeft = TimeLoop.loopLengthTicks;
        TimeLoop.config.ticksLeft = TimeLoop.config.loopLengthTicks;

        TimeLoop.trackItems = baseConfig.trackItems;
        TimeLoop.config.trackItems = baseConfig.trackItems;

        TimeLoop.loopType = baseConfig.loopType;
        TimeLoop.config.loopType = baseConfig.loopType;

        TimeLoop.displayTimeInTicks = baseConfig.displayTimeInTicks;
        TimeLoop.config.displayTimeInTicks = baseConfig.displayTimeInTicks;
        
        TimeLoop.executeCommand("mocap playback stop_all");
        TimeLoop.loopSceneManager.forEachPlayerSceneName(playerSceneName -> {
            TimeLoop.executeCommand(String.format("mocap scenes remove %s", playerSceneName));
            TimeLoop.executeCommand(String.format("mocap scenes add %s", playerSceneName));
        });

        TimeLoop.loopIteration = 0;
        TimeLoop.config.loopIteration = 0;
        TimeLoop.config.save();

        source.sendSuccess(() -> Component.literal("Loop reset!"), true);
        return 1;
    }
}