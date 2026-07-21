package com.vltno.timeloop.fabric;

import com.vltno.timeloop.*;
import com.vltno.timeloop.commands.LoopCommands;
import com.vltno.timeloop.fabric.events.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.gameevent.GameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class TimeLoopFabric implements ModInitializer {
    public static final Logger LOOP_LOGGER = LoggerFactory.getLogger("TimeLoop");
    
    @Override
    public void onInitialize() {
        LOOP_LOGGER.info("Initializing TimeLoop mod (Fabric)");

        FabricLoader loader = FabricLoader.getInstance();

        TimeLoop.voiceChatLoaded = loader.isModLoaded("voicechat");
        TimeLoop.voiceInteractionLoaded = loader.isModLoaded("vcinteraction");

        LOOP_LOGGER.info("Mod detection - voicechat: {}, vcinteraction: {}",
                TimeLoop.voiceChatLoaded, TimeLoop.voiceInteractionLoaded);

        // Register our own voice GameEvent (timeloop:voice) only when
        // voicechat-interaction is installed. We reuse voiceinteraction's
        // sculk frequency mapping for its event, but need our own event
        // for replay entities. When vcinteraction is NOT installed,
        // we skip registration entirely - no sculk/warden interaction.
        if (TimeLoop.voiceInteractionLoaded) {
            Registry.registerForHolder(
                    BuiltInRegistries.GAME_EVENT,
                    Identifier.fromNamespaceAndPath("timeloop", "voice"),
                    new GameEvent(16)
            );
        }

        TimeLoop.init();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // Only register commands on the logical server
            if (environment.includeDedicated || environment.includeIntegrated) {
                LoopCommands.register(dispatcher);
            }
        });

        EntitySleepEvents.STOP_SLEEPING.register((entity, blockPos) -> {
            if (entity.level().isClientSide()) { return; }
            EntitySleepFabricEvent.onStopSleeping(entity, blockPos);
        });

        ServerLifecycleEvents.SERVER_STARTED.register(LifecycleFabricEvent::onServerStart);

        ServerLifecycleEvents.SERVER_STOPPING.register(LifecycleFabricEvent::onServerStopping);

        ServerPlayConnectionEvents.JOIN.register(PlayConnectionFabricEvent::onJoin);

        ServerPlayConnectionEvents.DISCONNECT.register(PlayConnectionFabricEvent::onDisconnect);

        ServerPlayerEvents.AFTER_RESPAWN.register(PlayerFabricEvent::afterRespawn);

        ServerTickEvents.END_SERVER_TICK.register(TickFabricEvent::onEndServerTick);

        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(PlayerFabricEvent::dimensionChange);

        LOOP_LOGGER.info("TimeLoop mod initialized successfully (Fabric)");
    }
    
}