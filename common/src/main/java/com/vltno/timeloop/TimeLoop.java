package com.vltno.timeloop;

import com.mojang.serialization.DynamicOps;
import com.vltno.timeloop.types.LoopTypes;
import com.vltno.timeloop.types.MaxLoopsTypes;
import com.vltno.timeloop.types.RewindTypes;
import com.vltno.timeloop.types.SkinTypes;
import com.vltno.timeloop.compat.VoicechatInteractionCompat;
import com.vltno.timeloop.voicechat.Bridge;
import com.vltno.timeloop.voicechat.DummyBridge;
import com.vltno.timeloop.voicechat.VoicechatBridge;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.mt1006.mocap.api.v1.MocapAPI;
import net.mt1006.mocap.api.v1.controller.MocapController;
import net.mt1006.mocap.api.v1.controller.config.MocapOnChangeDimension;
import net.mt1006.mocap.api.v1.controller.config.MocapOnDeath;
import net.mt1006.mocap.api.v1.controller.config.MocapRecordingConfig;
import net.mt1006.mocap.api.v1.controller.playable.*;
import net.mt1006.mocap.api.v1.io.CommandInfo;
import net.mt1006.mocap.api.v1.io.CommandOutput;
import net.mt1006.mocap.mocap.playing.playable.SceneFile;
import net.mt1006.mocap.api.v1.modifiers.MocapModifiers;
import net.mt1006.mocap.api.v1.modifiers.MocapPlayerSkin;
import net.mt1006.mocap.api.v1.modifiers.MocapTime;
import net.mt1006.mocap.api.v1.modifiers.MocapTimeModifiers;
import net.mt1006.mocap.mocap.files.SceneData;
import net.mt1006.mocap.mocap.files.SceneFiles;
import net.mt1006.mocap.mocap.playing.PlaybackManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TimeLoop {
	public static final Logger LOOP_LOGGER = LoggerFactory.getLogger("TimeLoop");
	public static MinecraftServer server;
	public static ServerLevel serverLevel;

	// Mocap API controller — created on server start
	public static MocapController mocapController;

	// Per-player active recordings keyed by player name (for stop/save)
	public static final Map<String, MocapActiveRecording> activeRecordings = new ConcurrentHashMap<>();

	public static LoopBossBar loopBossBar;

	// These fields will be initialized from the configuration file.
	public static int loopIteration;
	public static int loopLengthTicks;
	public static long startTimeOfDay; // The Time the Loop was started
	public static long timeSetting;
	public static boolean trackTimeOfDay;
	public static boolean isLooping;
	public static int maxLoops;
    public static MaxLoopsTypes maxLoopsType;
	public static int tickCounter; // Tracks elapsed ticks
	public static int ticksLeft;

	// Whether mocap scene playback has been started for the current iteration.
	// Reset on iteration advance and server start. Used to trigger playback
	// when the first player joins mid-loop (e.g. after a server restart).
	public static boolean playbackStartedThisIteration;

	public static boolean showLoopInfo;
	public static boolean displayTimeInTicks;
	public static boolean trackItems;
	public static int entityTrackingDistance;
	public static LoopTypes loopType;
	public static boolean trackChat;
	public static boolean hurtLoopedPlayers;
	public static RewindTypes rewindType;
	public static boolean trackInventory;

    public static boolean voiceChatLoaded;
    public static boolean vcInteractionLoaded;

    public static Bridge voiceBridge;

    // Voice interaction settings (sculk / warden game events)
    public static boolean voiceInteractionEnabled;
    public static int voiceInteractionThresholdDb;
    public static int voiceInteractionCooldownTicks;
    public static float voiceAudioDistance;

	// The configuration object loaded from disk
	public static TimeLoopConfig config;

	// The loop scene manager object
	public static LoopSceneManager loopSceneManager;

	// Get the world folder path for config/recording loading
	public static Path worldFolder;

	public static void init() {

        if (voiceChatLoaded) {
            voiceBridge = new VoicechatBridge();
        } else {
            voiceBridge = new DummyBridge();
        }

		loopBossBar = new LoopBossBar();

		LOOP_LOGGER.info("Initializing TimeLoop mod (Common)");
	}

	/**
	 * Returns the {@link CommandInfo} for the current server level from the mocap controller.
	 */
	public static CommandInfo getMocapCommandInfo() {
		return mocapController.getCommandInfo();
	}

    public static void handlePlayerDimensionChange(ServerPlayer player) {
        if (!isLooping) return;

        PlayerData playerData = loopSceneManager.getRecordingPlayer(player.getName().getString());

        if (!playerData.getActive()) return; // Shouldn't be possible but just in case

        ResourceKey<Level> currentDimension = player.level().dimension();
        ResourceKey<Level> lastDimension = playerData.getLastDimensionKey();

        // Check if dimension has actually changed since the last update
        if (currentDimension.equals(lastDimension)) {
            return;
        }

        playerData.incrementActiveRecordingIndex();
        playerData.nextSegment();

        playerData.addTempOffset(convertTicksToSeconds(tickCounter));

        LOOP_LOGGER.info("Player '{}' changed dimension. New active recording index is now {}. Temp offsets are {}",
                player.getName().getString(), playerData.getActiveRecordingIndex(), playerData.getTempOffsets());

        // Update the player's state
        playerData.setLastDimensionKey(currentDimension);
    }

	/**
	 * Runs the next iteration of the loop.
	 */
	public static void runLoopIteration() {
        if (!isLooping) {
            LOOP_LOGGER.warn("Tried to iterate but not looping!");
            return;
        }
        if (loopIteration + 1 >= maxLoops && maxLoops != 0 && maxLoopsType == MaxLoopsTypes.STOP_LOOP) {
            stopLoop();
            return;
        }
		LOOP_LOGGER.info("Starting iteration {} of loop", loopIteration);
		// Save voice audio to disk before advancing the iteration
		voiceBridge.saveAudio();
		saveRecordings();
		removeOldSceneEntries();
		PlaybackManager.stopAll(CommandOutput.DUMMY, null);
		playbackStartedThisIteration = false;
		startRecordings();
		if (trackTimeOfDay) { serverLevel.setDayTime(startTimeOfDay); }

        loopIteration++;

		// Rewind active players (inventory, position) before starting playback
		loopSceneManager.forEachRecordingPlayer(playerData -> {
            if (!playerData.getActive()) return;

			String playerName = playerData.getName();
			Vec3 startPosition = playerData.getStartPosition();
			Vec3 joinPosition = playerData.getJoinPosition();
			CompoundTag inventoryTag = playerData.getInventoryTag();
			
			Player player = server.getPlayerList().getPlayerByName(playerName);
            if (player == null) {
                LOOP_LOGGER.warn("Player {} is offline, skipping rewind.", playerName);
                return;
            }

			HolderLookup.Provider provider = server.registryAccess();

			if (trackInventory) {
				loadFullInventory(player, inventoryTag, provider);
			}

            if (!rewindType.equals(RewindTypes.NONE)) {
                ServerLevel targetLevel = (ServerLevel) player.level();
                Set<Relative> absoluteMovement = Collections.emptySet();
                float yaw = player.getYRot();
                float pitch = player.getXRot();
                boolean setCamera = false;

                Vec3 teleportPosition = Vec3.ZERO;
                switch (rewindType) {
                    case START_POSITION -> {
                        if (startPosition == null) {
                            LOOP_LOGGER.error("Player {} has no start position yet. Defaulting to join position.", playerName);
                            teleportPosition = joinPosition;
                        } else {
                            teleportPosition = startPosition;
                        }
                    }
                    case JOIN_POSITION -> {
                        if (joinPosition == null) {
                            LOOP_LOGGER.error("Player {} has no join position yet. (somehow??)", playerName);
                            return;
                        }
                        teleportPosition = joinPosition;
                    }
                    case SPAWN_POSITION -> {
                        Vec3 spawnPosition = server.overworld().getRespawnData().pos().getBottomCenter();
                        if (spawnPosition == null) {
                            LOOP_LOGGER.error("Server has no spawn position yet. (somehow??)");
                            return;
                        }
                        teleportPosition = spawnPosition;
                    }
                }
                player.teleportTo(targetLevel, teleportPosition.x, teleportPosition.y, teleportPosition.z, absoluteMovement, yaw, pitch, setCamera);
            }
            playerData.setLastDimensionKey(player.level().dimension());
		});

		// Start mocap scene + voice playback for all players
		startPlaybackForAllPlayers();

		config.loopIteration = loopIteration;
		config.save();
		LOOP_LOGGER.info("Completed loop iteration {}", loopIteration - 1);
	}

	/**
	 * Starts and initialises the loop.
	 */
	public static void startLoop() {
		if (isLooping) {
			LOOP_LOGGER.info("Attempted to start already running recording loop");
			return;
		}
		if (showLoopInfo) {
			loopBossBar.visible(loopType.equals(LoopTypes.TICKS) || loopType.equals(LoopTypes.TIME_OF_DAY));
		}
		
		loopSceneManager.forEachRecordingPlayer(playerData -> {
            if (!playerData.getActive()) return;

			String playerName = playerData.getName();
			Player player = server.getPlayerList().getPlayerByName(playerName);

			HolderLookup.Provider provider = server.registryAccess();

			CompoundTag invTag = saveFullInventory(player, provider);
    		playerData.setInventoryTag(invTag);

			playerData.setStartPosition(server.getPlayerList().getPlayerByName(playerName).position());
		});

        startTimeOfDay = serverLevel.getDayTime();
		isLooping = true;
		config.isLooping = true;
		tickCounter = 0;
		ticksLeft = loopLengthTicks;
		playbackStartedThisIteration = false;
		LOOP_LOGGER.info("Starting Loop");
		startRecordings();
	}

	/**
	 * Starts the recordings via the mocap API.
	 */
	public static void startRecordings() {
		loopSceneManager.forEachRecordingPlayer(playerData -> {
            if (!playerData.getActive()) return;

			String playerName = playerData.getName();
			playerData.resetActiveRecordingIndex();
			playerData.resetTempOffsets();
			playerData.resetSegments();

			ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
			if (player == null) {
				LOOP_LOGGER.warn("Cannot start recording for offline player: {}", playerName);
				return;
			}

			MocapRecordingConfig recConfig = MocapRecordingConfig.createFromSettings();
			MocapActiveRecording recording = mocapController.startRecording(player, recConfig, true);
			if (recording != null) {
				activeRecordings.put(playerName, recording);
				LOOP_LOGGER.info("Started mocap recording for player: {}", playerName);
			} else {
				LOOP_LOGGER.error("Failed to start mocap recording for player: {}", playerName);
			}
		});
	}

	/**
	 * Saves the recordings via the mocap API.
	 */
	public static void saveRecordings() {
		CommandOutput out = CommandOutput.LOGS;

		loopSceneManager.forEachRecordingPlayer(playerData -> {
			if (!playerData.getActive()) return;

			String playerName = playerData.getName();
			String playerSceneName = loopSceneManager.getPlayerSceneName(playerName);

			MocapActiveRecording activeRec = activeRecordings.remove(playerName);
			if (activeRec == null || !activeRec.isValid()) {
				LOOP_LOGGER.warn("No active mocap recording found for player: {}", playerName);
				return;
			}

			// Stop the recording — this puts it into 'waiting for decision' state
			activeRec.stop(out);

			// Save to a named recording file
			String recordingName = String.format("%s_loop%d", playerName.toLowerCase(), loopIteration);
			MocapRecordingFile savedFile = activeRec.save(out, recordingName);
			if (savedFile == null) {
				LOOP_LOGGER.error("Failed to save mocap recording '{}' for player: {}", recordingName, playerName);
				return;
			}
			LOOP_LOGGER.info("Saved mocap recording '{}' for player: {}", recordingName, playerName);

			// Add the recording to the player's scene with a start_delay modifier
			MocapSceneFile sceneFile = SceneFile.get(out, playerSceneName);
			if (sceneFile == null || !sceneFile.exists()) {
				LOOP_LOGGER.error("Scene file '{}' not found for player: {}", playerSceneName, playerName);
				return;
			}

			float delaySeconds = playerData.getTempOffset(0);
			MocapModifiers modifiers = MocapModifiers.empty();
			if (delaySeconds > 0f) {
				MocapTimeModifiers timeMods = modifiers.getTimeModifiers()
						.withStartDelay(MocapTime.fromSeconds(delaySeconds));
				modifiers = modifiers.withTimeModifiers(timeMods);
			}

			sceneFile.add(out, savedFile, modifiers);
			playerData.incrementActiveSubsceneIndex();

			LOOP_LOGGER.info("Added recording '{}' to scene '{}' with delay {}s",
					recordingName, playerSceneName, delaySeconds);
		});
	}

	/**
	 * Stops the loop.
	 */
	public static void stopLoop() {
		if (isLooping) {
			LOOP_LOGGER.info("Stopping loop");

			// Save voice audio to disk FIRST (synchronously!) before anything is torn down.
			// saveAudio() saves ALL players regardless of active status.
			try {
				voiceBridge.saveAudio().get(10, java.util.concurrent.TimeUnit.SECONDS);
			} catch (Exception e) {
				LOOP_LOGGER.error("Voice audio save failed or timed out during stopLoop", e);
			}

			isLooping = false;
            config.isLooping = false;

            tickCounter = 0;
            config.tickCounter = 0;

            ticksLeft = loopLengthTicks;
            config.ticksLeft = loopLengthTicks;

			loopBossBar.visible(false);
			saveRecordings();
			loopSceneManager.saveRecordingPlayers();
			PlaybackManager.stopAll(CommandOutput.DUMMY, null);
            voiceBridge.stopPlayback("");
			VoicechatInteractionCompat.clearCooldowns();
			LOOP_LOGGER.info("Loop stopped!");
		}
	}

	/**
	 * Starts mocap scene playback and voice playback for all recording players
	 * that have previous iterations. Called both at iteration boundaries and
	 * when the first player joins mid-loop (e.g. after a server restart).
	 */
	public static void startPlaybackForAllPlayers() {
		if (loopIteration <= 0) return; // Nothing to play back on iteration 0

		LOOP_LOGGER.info("Starting playback for all players (iteration {})", loopIteration);

		CommandInfo info = getMocapCommandInfo();

		loopSceneManager.forEachRecordingPlayer(playerData -> {
			String playerName = playerData.getName();
			String playerNickname = playerData.getNickname();
			Skin playerSkin = playerData.getSkin();
			String playerSceneName = loopSceneManager.getPlayerSceneName(playerName);

			// Build modifiers with player name and skin
			MocapPlayerSkin mocapSkin = switch (playerSkin.skinType) {
				case MINESKIN -> MocapPlayerSkin.fromMineSkin(playerSkin.value);
				case FILE -> MocapPlayerSkin.fromFile(playerSkin.value);
				default -> MocapPlayerSkin.fromPlayer(playerSkin.value);
			};
			MocapModifiers modifiers = MocapModifiers.playerName(playerNickname)
					.withPlayerSkin(mocapSkin);

			// Get the scene file and start playback
			MocapSceneFile sceneFile = SceneFile.get(CommandOutput.LOGS, playerSceneName);
			if (sceneFile != null && sceneFile.exists()) {
				// Start mocap scene FIRST — this spawns the FakePlayer entities synchronously
				sceneFile.startPlayback(info, modifiers);
				LOOP_LOGGER.info("Started mocap playback for scene '{}' as '{}'", playerSceneName, playerNickname);

				// Collect all spawned entities for this player's nickname.
				// Entities are already in the world from startPlayback() above.
				var entities = VoicechatBridge.findAllMocapEntities(playerNickname);
				LOOP_LOGGER.info("Found {} mocap entities for '{}'", entities.size(), playerNickname);

				// Start voice with pre-assigned entity list — no race condition
				voiceBridge.onStartPlayback(playerData, entities);
			} else {
				LOOP_LOGGER.warn("Scene file '{}' not found, skipping playback for player: {}", playerSceneName, playerName);
			}
		});

		playbackStartedThisIteration = true;
	}

	public static void modifyPlayerAttributes(String targetPlayerName, String newPlayerNickname, Skin newSkin) {
        PlayerData targetPlayer = loopSceneManager.getRecordingPlayer(targetPlayerName);

        targetPlayer.setNickname(newPlayerNickname);
        targetPlayer.setSkin(newSkin);
        LOOP_LOGGER.info("Modified loop attributes for player '{}' -> '{}' with skin '{}'", targetPlayerName, newPlayerNickname, newSkin.value);
	}

	/**
	 * Updates the entities to be tracked for recording and playback settings.
	 * When items are enabled, also sets the entity tracking distance so items
	 * are captured within a reasonable range. When disabled, resets distance to 1.
	 *
	 * @param items If true, includes items in the tracking list and uses the
	 *              configured tracking distance. If false, excludes items and
	 *              sets distance to 1 (minimum, just vehicles nearby).
	 */
	public static void updateEntitiesToTrack(boolean items) {
		if (mocapController == null) return;
		String entitiesToTrack = "@vehicles" + (items ? ";@items" : "");
		int distance = items ? entityTrackingDistance : 1;
		mocapController.setSetting("track_entities", entitiesToTrack);
		mocapController.setSetting("play_entities", entitiesToTrack);
		mocapController.setSetting("entity_tracking_distance", String.valueOf(distance));
	}

	public static void updateInfoBar(int time, int timeLeft) {
		if (showLoopInfo && isLooping) {
			if (displayTimeInTicks) { loopBossBar.setBossBarName("Time Left: " + timeLeft); }
			else {loopBossBar.setBossBarName("Time Left: " + convertTicksToTime(timeLeft));}

			loopBossBar.setBossBarPercentage(time, timeLeft);
		}
	}

	/**
	 * Deletes a recording file directly from disk.
	 * Mocap's own remove does not delete the file, so we do it ourselves.
	 */
	public static void removeRecording(String recording) {
		try {
			Path recordingPath = worldFolder.resolve("mocap_files").resolve("recordings").resolve(recording + ".mcmocap_rec");
			Files.delete(recordingPath);
			LOOP_LOGGER.info("Deleted recording file at {}", recordingPath);
		} catch (IOException e) {
			LOOP_LOGGER.error("Error deleting recording file '{}'", recording);
		}
	}

	/**
	 * Removes outdated entries from the scene file to ensure the number of subscenes does not exceed the maximum allowed loops.
	 * The method checks if there are more recorded subscenes in the scene file than the value specified by maxLoops. If so,
	 * it removes the oldest entries to maintain the desired number. The updated data is then saved back to the file.
	 */
	private static void removeOldSceneEntries() {
		if (maxLoops < 1) return;
		if (maxLoopsType != MaxLoopsTypes.KEEP_NEWEST && maxLoopsType != MaxLoopsTypes.KEEP_NEWEST_DELETE) return;
		if ((loopIteration + 1) <= maxLoops) return;

		CommandOutput out = CommandOutput.LOGS;
		int entriesToRemove = loopIteration - maxLoops;

		loopSceneManager.forEachRecordingPlayer(playerData -> {
			String playerSceneName = loopSceneManager.getPlayerSceneName(playerData.getName());
			if (playerSceneName == null || playerSceneName.isBlank()) return;

			MocapSceneFile sceneFile = SceneFile.get(out, playerSceneName);
			if (sceneFile == null || !sceneFile.exists()) return;

			List<? extends MocapSceneElement> elements = sceneFile.getAll(out);
			if (elements == null || elements.size() <= maxLoops) return;

			List<MocapSceneElement> toKeep = new ArrayList<>();
			for (MocapSceneElement element : elements) {
				String name = element.getName();
				String[] parts = name.split("_");
				try {
					// Extract loop number from recording name like "player_loop5"
					int elemLoop = -1;
					for (String part : parts) {
						if (part.startsWith("loop")) {
							elemLoop = Integer.parseInt(part.substring(4));
							break;
						}
					}
					if (elemLoop > entriesToRemove) {
						toKeep.add(element);
					} else {
						if (maxLoopsType == MaxLoopsTypes.KEEP_NEWEST_DELETE) {
							removeRecording(name);
						}
					}
				} catch (NumberFormatException e) {
					toKeep.add(element); // Keep entries we can't parse
				}
			}

			playerData.setActiveSubsceneIndex(toKeep.size());
			sceneFile.replaceAll(out, toKeep);
			LOOP_LOGGER.info("Trimmed scene '{}': kept {} of {} entries",
					playerSceneName, toKeep.size(), elements.size());
		});
	}

	/**
	 * Converts time in ticks to HH:MM:SS
	 */
	public static String convertTicksToTime(int ticks) {
		int time = ticks / 20;
		int hours = time / 3600;
		int minutes = (time % 3600) / 60;
		int seconds = time % 60;
		return String.format("%02d:%02d:%02d", hours, minutes, seconds);
	}

    public static float convertTicksToSeconds(int ticks) {
        return ticks / 20f;
    }

    private static final EquipmentSlot[] ARMOR_SLOTS = new EquipmentSlot[] {
            EquipmentSlot.FEET,
            EquipmentSlot.LEGS,
            EquipmentSlot.CHEST,
            EquipmentSlot.HEAD
    };

    private static CompoundTag stackToNbt(ItemStack stack, HolderLookup.Provider provider) {
        if (stack.isEmpty()) {
            return new CompoundTag();
        }

        DynamicOps<Tag> ops = provider.createSerializationContext(NbtOps.INSTANCE);

        // Encode the ItemStack using its static CODEC
        Tag resultTag = ItemStack.CODEC.encodeStart(ops, stack)
                .getOrThrow(error -> new IllegalStateException("Failed to encode ItemStack: " + error));

        return (CompoundTag) resultTag;
    }

    /**
     * Helper method to deserialize an ItemStack from a CompoundTag using its Codec.
     * This bypasses the 'parseOptional' resolution error.
     */
    private static ItemStack stackFromNbt(CompoundTag itemTag, HolderLookup.Provider provider) {
        if (itemTag.isEmpty()) {
            return ItemStack.EMPTY;
        }

        DynamicOps<Tag> ops = provider.createSerializationContext(NbtOps.INSTANCE);

        // Decode the ItemStack using its static CODEC
        return ItemStack.CODEC.parse(ops, itemTag)
                .result().orElse(ItemStack.EMPTY);
    }

    public static CompoundTag saveFullInventory(Player player, HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        Inventory inventory = player.getInventory();

        // 1. Main inventory (Slots 0-35)
        ListTag main = new ListTag();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i); // Use getItem(index)

            main.add(stackToNbt(stack, provider)); // Use Codec helper
        }
        tag.put("main", main);

        // 2. Armor (Iterate using the explicit, fixed array)
        ListTag armor = new ListTag();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot); // Use player.getItemBySlot()

            armor.add(stackToNbt(stack, provider)); // Use Codec helper
        }
        tag.put("armor", armor);

        // 3. Offhand (Single slot)
        ListTag offhand = new ListTag();
        ItemStack offhandStack = player.getItemBySlot(EquipmentSlot.OFFHAND);

        offhand.add(stackToNbt(offhandStack, provider)); // Use Codec helper
        tag.put("offhand", offhand);

        return tag;
    }

    public static void loadFullInventory(Player player, CompoundTag tag, HolderLookup.Provider provider) {
        Inventory inventory = player.getInventory();

        // FIX 1: CompoundTag.getList(String key) now returns Optional<ListTag>.
        // We use .orElseGet(ListTag::new) to handle the Optional and missing tag.
        ListTag main = tag.getList("main").orElseGet(ListTag::new);

        // 1. Main inventory
        for (int i = 0; i < 36; i++) {

            // FIX 2: ListTag.getCompound(i) returns Optional<CompoundTag>.
            // We check bounds and unwrap the Optional, defaulting to a new empty tag.
            Optional<CompoundTag> optionalItemTag = i < main.size()
                    ? main.getCompound(i)
                    : Optional.empty();

            CompoundTag itemTag = optionalItemTag.orElseGet(CompoundTag::new);

            // FIX 3: Use stackFromNbt helper to handle ItemStack deserialization.
            ItemStack stack = stackFromNbt(itemTag, provider);

            inventory.setItem(i, stack);
        }

        // 2. Armor (Load using the explicit, fixed array)
        // FIX 1: CompoundTag.getList(String key) now returns Optional<ListTag>.
        ListTag armor = tag.getList("armor").orElseGet(ListTag::new);

        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            EquipmentSlot slot = ARMOR_SLOTS[i];

            // FIX 2: ListTag.getCompound(i) returns Optional<CompoundTag>.
            Optional<CompoundTag> optionalItemTag = i < armor.size()
                    ? armor.getCompound(i)
                    : Optional.empty();

            CompoundTag itemTag = optionalItemTag.orElseGet(CompoundTag::new);

            // FIX 3: Use stackFromNbt helper.
            ItemStack stack = stackFromNbt(itemTag, provider);

            player.setItemSlot(slot, stack);
        }

        // 3. Offhand (Single slot)
        // FIX 1: CompoundTag.getList(String key) now returns Optional<ListTag>.
        ListTag offhand = tag.getList("offhand").orElseGet(ListTag::new);

        if (!offhand.isEmpty()) {
            // FIX 2: ListTag.getCompound(0) returns Optional<CompoundTag>.
            Optional<CompoundTag> optionalItemTag = offhand.getCompound(0);

            CompoundTag itemTag = optionalItemTag.orElseGet(CompoundTag::new);

            // FIX 3: Use stackFromNbt helper.
            ItemStack stack = stackFromNbt(itemTag, provider);

            player.setItemSlot(EquipmentSlot.OFFHAND, stack);
        } else {
            player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        }
    }
}