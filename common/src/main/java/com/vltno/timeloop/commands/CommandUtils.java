package com.vltno.timeloop.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.vltno.timeloop.PlayerData;
import com.vltno.timeloop.TimeLoop;
import com.vltno.timeloop.types.TypeInterface;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class CommandUtils {
    public static List<String> getTrackedItemsFromReflection() {
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

    public static @NotNull String formatList(List<String> items) {
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

    public static <T extends Enum<T>> CompletableFuture<Suggestions> EnumSuggestion(SuggestionsBuilder builder, Class<T> enumClass) {
        return SharedSuggestionProvider.suggest(Arrays.stream(enumClass.getEnumConstants()).filter(e -> e instanceof TypeInterface).map(e -> ((TypeInterface) e).getCommandName()), builder);
    }

    public static CompletableFuture<Suggestions> PlayerSuggestion(SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(TimeLoop.loopSceneManager.getRecordingPlayers().stream().filter(PlayerData::getActive).map(PlayerData::getName), builder);
    }

    public static CompletableFuture<Suggestions> FileSuggestion(SuggestionsBuilder builder, Path path, boolean includeExtension) {
        try {
            return SharedSuggestionProvider.suggest(Files.walk(path).toList().stream().filter(p -> p.toFile().isFile()).map(p -> {
                String filepath = Arrays.stream(p.toString().replace("\\", "/").split(path.toString().replace("\\", "/") + "/")).toList().getLast().replace("\\", "/");

                return (includeExtension) ? filepath : filepath.substring(0, filepath.lastIndexOf("."));
            }), builder);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static CompletableFuture<Suggestions> SkinSuggestion(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return FileSuggestion(builder, TimeLoop.worldFolder.resolve("mocap_files").resolve("skins"), false);
    }

    @SuppressWarnings("unchecked")
    public static LiteralArgumentBuilder<CommandSourceStack> toggleCommand(Supplier<Boolean> getter, String name, Command command) {
        String spacedName = Pattern.compile("(?<=[a-z])(?=[A-Z])").matcher(name).replaceAll(" ").toLowerCase();
        String finalName = spacedName.substring(0,1).toUpperCase() + spacedName.substring(1).toLowerCase();

        LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(name);

        return LiteralArgumentBuilder.<CommandSourceStack>literal(name)
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(finalName + " is set to: " + getter.get()), false);
                    return 1;
                })
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(command));
    }
}
