package com.vltno.timeloop.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoopCommands {
    public static final Logger LOOP_COMMANDS_LOGGER = LoggerFactory.getLogger("LoopCommands");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher)
    {
        LiteralArgumentBuilder<CommandSourceStack> commandBuilder = Commands.literal("loop");

        BaseCommands.register(commandBuilder);
        SettingsCommands.register(commandBuilder);

        dispatcher.register(commandBuilder);
    }
}