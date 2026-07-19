package com.vltno.timeloop;

import com.mojang.serialization.DynamicOps;
import com.vltno.timeloop.types.LoopTypes;
import com.vltno.timeloop.types.MaxLoopsTypes;
import com.vltno.timeloop.types.RewindTypes;
import com.vltno.timeloop.compat.VoicechatInteractionCompat;
import com.vltno.timeloop.compat.voicechat.AudioPersistence;
import com.vltno.timeloop.compat.VoicechatCompat;
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
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.mt1006.mocap.api.v1.controller.MocapController;
import net.mt1006.mocap.api.v1.controller.config.MocapRecordingConfig;
import net.mt1006.mocap.api.v1.controller.playable.*;
import net.mt1006.mocap.api.v1.io.CommandInfo;
import net.mt1006.mocap.api.v1.io.CommandOutput;
import net.mt1006.mocap.mocap.playing.playable.SceneFile;
import net.mt1006.mocap.api.v1.modifiers.MocapModifiers;
import net.mt1006.mocap.api.v1.modifiers.MocapPlayerSkin;
import net.mt1006.mocap.api.v1.modifiers.MocapTime;
import net.mt1006.mocap.api.v1.modifiers.MocapTimeModifiers;
import net.mt1006.mocap.mocap.playing.PlaybackManager;
import net.mt1006.mocap.mocap.playing.playable.ActiveRecording;
import net.mt1006.mocap.mocap.recording.RecordingContext;
import net.mt1006.mocap.mocap.recording.RecordingManager;
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

	// Mocap API controller - created on server start
	public static MocapController mocapController;

	// Per-player active recordings keyed by player name (for stop/save)
	public static final Map<String, MocapActiveRecording> activeRecordings = new ConcurrentHashMap<>();

	public static LoopBossBar loopBossBar;

	// Initialised from the config
	public static int loopIteration;
	public static int loopLengthTicks;
	public static long startTimeOfDay;
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
    public static boolean voiceInteractionLoaded;

    // Voice interaction settings (sculk / warden game events)
    public static boolean voiceInteractionEnabled;
    public static int voiceInteractionThresholdDb;
    public static int voiceInteractionCooldownTicks;
    public static float voiceAudioDistance;

    // Whether to show the SVC speaker icon above looped player entities
    public static boolean showPlayerVoiceIcon;

    // Whether voice chat audio is recorded during the loop
    public static boolean trackVoice;
		public static TimeLoopConfig config;
	public static LoopSceneManager loopSceneManager;
	public static Path worldFolder;

	public static final java.util.Queue<Runnable> delayedTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();

	public static void queueTask(Runnable task) {
		delayedTasks.add(task);
	}

	public static void init() {
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
        if (playerData == null || !playerData.getActive()) return;

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
		VoicechatCompat.saveAudio();
		saveRecordings();
		removeOldSceneEntries();
		PlaybackManager.stopAll(CommandOutput.DUMMY, null);
        VoicechatCompat.stopPlayback(null);
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
                // Teleport to the dimension the player was in when the loop started,
                // not whatever dimension they happen to be in now.
                ResourceKey<Level> startDimKey = playerData.getStartDimensionKey();
                ServerLevel targetLevel = startDimKey != null
                        ? server.getLevel(startDimKey)
                        : (ServerLevel) player.level();
                if (targetLevel == null) targetLevel = (ServerLevel) player.level();
                Set<RelativeMovement> absoluteMovement = Collections.emptySet();
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
                        Vec3 spawnPosition = server.overworld().getSharedSpawnPos().getBottomCenter();
                        if (spawnPosition == null) {
                            LOOP_LOGGER.error("Server has no spawn position yet. (somehow??)");
                            return;
                        }
                        teleportPosition = spawnPosition;
                    }
                }
                player.teleportTo(targetLevel, teleportPosition.x, teleportPosition.y, teleportPosition.z, absoluteMovement, yaw, pitch);
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
			if (player == null) {
				LOOP_LOGGER.warn("Cannot initialize loop for offline player: {}", playerName);
				return;
			}

			HolderLookup.Provider provider = server.registryAccess();

			CompoundTag invTag = saveFullInventory(player, provider);
    		playerData.setInventoryTag(invTag);

			playerData.setStartPosition(player.position());
			playerData.setStartDimensionKey(player.level().dimension());
			playerData.setLastDimensionKey(player.level().dimension());
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
	 * Saves the recordings via the mocap API for all active players.
	 */
	public static void saveRecordings() {
		loopSceneManager.forEachRecordingPlayer(playerData -> {
			if (!playerData.getActive()) return;
			saveRecordingForPlayer(playerData);
		});
	}

	/**
	 * Saves <b>all</b> mocap recordings for a single player and adds them to
	 * the player's scene file.
	 */
	public static void saveRecordingForPlayer(PlayerData playerData) {
		CommandOutput out = CommandOutput.LOGS;
		String playerName = playerData.getName();
		String playerSceneName = loopSceneManager.getPlayerSceneName(playerName);

		MocapSceneFile sceneFile = SceneFile.get(out, playerSceneName);
		if (sceneFile == null || !sceneFile.exists()) {
			LOOP_LOGGER.error("Scene file '{}' not found for player: {}", playerSceneName, playerName);
			return;
		}

		ArrayList<Float> offsets = playerData.getTempOffsets();
		int recordingIndex = 0;

		// ── 1. Save the primary recording FIRST ──────────────────────────────
		MocapActiveRecording primaryRec = activeRecordings.remove(playerName);
		if (primaryRec != null && primaryRec.isValid()) {
			RecordingContext primaryCtx = ((ActiveRecording) primaryRec).ctx;
			primaryCtx.stop(out);
			
			if (primaryCtx.state == RecordingContext.State.WAITING_FOR_DECISION) {
				String recordingName = String.format("%s_loop%d", playerName.toLowerCase(), loopIteration);
				MocapRecordingFile savedFile = primaryRec.save(out, recordingName);
				if (savedFile != null) {
					float delay = (recordingIndex < offsets.size()) ? offsets.get(recordingIndex) : 0f;
					sceneFile.add(out, savedFile, buildDelayModifiers(delay));
					LOOP_LOGGER.info("Saved '{}' to scene '{}' with delay {}s",
							recordingName, playerSceneName, delay);
					recordingIndex++;
				} else {
					LOOP_LOGGER.error("Failed to save primary recording '{}' for player: {}",
							recordingName, playerName);
				}
			} else {
				LOOP_LOGGER.warn("Primary recording for '{}' failed to stop properly (state: {}). Skipping save.", playerName, primaryCtx.state);
			}
		} else {
			LOOP_LOGGER.warn("No primary mocap recording found for player: {}", playerName);
		}

		// ── 2. Find and save split recording contexts ──────────────────────
		List<RecordingContext> splitContexts = new ArrayList<>();
		for (RecordingContext ctx : new ArrayList<>(RecordingManager.allContexts())) {
			if (!ctx.source.name.equals("+timeloop")) continue;
			if (!ctx.recordedPlayer.getName().getString().equals(playerName)) continue;
			if (ctx.isRemoved()) continue;
			splitContexts.add(ctx);
		}

		for (RecordingContext ctx : splitContexts) {
			ctx.stop(out);
			if (ctx.state != RecordingContext.State.WAITING_FOR_DECISION) {
				LOOP_LOGGER.warn("Split context for {} in unexpected state: {}, skipping",
						playerName, ctx.state);
				continue;
			}

			MocapActiveRecording splitRec = ActiveRecording.get(ctx);
			String recordingName = String.format("%s_loop%d_%d",
					playerName.toLowerCase(), loopIteration, recordingIndex + 1);

			MocapRecordingFile savedFile = splitRec.save(out, recordingName);
			if (savedFile != null) {
				float delay = (recordingIndex < offsets.size()) ? offsets.get(recordingIndex) : 0f;
				sceneFile.add(out, savedFile, buildDelayModifiers(delay));
				LOOP_LOGGER.info("Saved split '{}' to scene '{}' with delay {}s",
						recordingName, playerSceneName, delay);
				recordingIndex++;
			} else {
				LOOP_LOGGER.error("Failed to save split recording '{}' for player: {}",
						recordingName, playerName);
			}
		}

		if (recordingIndex == 0) {
			LOOP_LOGGER.warn("No recordings saved for player: {}", playerName);
		}
	}

	/**
	 * Builds a {@link MocapModifiers} with a start delay, or empty if delay is zero.
	 */
	private static MocapModifiers buildDelayModifiers(float delaySeconds) {
		MocapModifiers modifiers = MocapModifiers.empty();
		if (delaySeconds > 0f) {
			MocapTimeModifiers timeMods = modifiers.getTimeModifiers()
					.withStartDelay(MocapTime.fromSeconds(delaySeconds));
			modifiers = modifiers.withTimeModifiers(timeMods);
		}
		return modifiers;
	}

	/**
	 * Stops the loop.
	 */
	public static void stopLoop() {
		if (isLooping) {
			LOOP_LOGGER.info("Stopping loop");

			// Save voice audio to disk FIRST (synchronously!) before anything is torn down.
			try {
				VoicechatCompat.saveAudio().get(10, java.util.concurrent.TimeUnit.SECONDS);
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
            VoicechatCompat.stopPlayback(null);
			VoicechatInteractionCompat.clearCooldowns();
			config.save();
			LOOP_LOGGER.info("Loop stopped!");
		}
	}

	/**
	 * Starts mocap scene playback and voice playback for all recording players
	 * that have previous iterations.
	 */
	public static void startPlaybackForAllPlayers() {
		if (loopIteration <= 0) return;

		LOOP_LOGGER.info("Starting playback for all players (iteration {})", loopIteration);

		CommandInfo info = getMocapCommandInfo();

		loopSceneManager.forEachRecordingPlayer(playerData -> {
			String playerName = playerData.getName();
			String playerNickname = playerData.getNickname();
			Skin playerSkin = playerData.getSkin();
			String playerSceneName = loopSceneManager.getPlayerSceneName(playerName);

			MocapPlayerSkin mocapSkin = switch (playerSkin.skinType) {
				case MINESKIN -> MocapPlayerSkin.fromMineSkin(playerSkin.value);
				case FILE -> MocapPlayerSkin.fromFile(playerSkin.value);
				default -> MocapPlayerSkin.fromPlayer(playerSkin.value);
			};
			MocapModifiers modifiers = MocapModifiers.playerName(playerNickname)
					.withPlayerSkin(mocapSkin);

			MocapSceneFile sceneFile = SceneFile.get(CommandOutput.LOGS, playerSceneName);
			if (sceneFile != null && sceneFile.exists()) {
				try {
					sceneFile.startPlayback(info, modifiers);
					LOOP_LOGGER.info("Started mocap playback for scene '{}' as '{}'", playerSceneName, playerNickname);
				} catch (Exception e) {
					LOOP_LOGGER.error("Failed to start mocap playback for scene '{}': {}", playerSceneName, e.getMessage(), e);
				}

				var entities = VoicechatCompat.findAllMocapEntities(playerNickname);
				LOOP_LOGGER.info("Found {} mocap entities for '{}'", entities.size(), playerNickname);

				List<String> elementNames = new ArrayList<>();
				var elements = sceneFile.getAll(CommandOutput.LOGS);
				if (elements != null) {
					for (var elem : elements) {
						elementNames.add(elem.getName());
					}
				}

				VoicechatCompat.onStartPlayback(playerData, entities, elementNames);
			} else {
				LOOP_LOGGER.warn("Scene file '{}' not found, skipping playback for player: {}", playerSceneName, playerName);
			}
		});

		playbackStartedThisIteration = true;
	}

	public static void modifyPlayerAttributes(String targetPlayerName, String newPlayerNickname, Skin newSkin) {
        PlayerData targetPlayer = loopSceneManager.getRecordingPlayer(targetPlayerName);
        if (targetPlayer == null) {
            LOOP_LOGGER.error("Cannot modify attributes: player '{}' not found in recording players", targetPlayerName);
            return;
        }

        targetPlayer.setNickname(newPlayerNickname);
        targetPlayer.setSkin(newSkin);
        LOOP_LOGGER.info("Modified loop attributes for player '{}' -> '{}' with skin '{}'", targetPlayerName, newPlayerNickname, newSkin.value);
	}

	/**
	 * Updates the entities to be tracked for recording and playback settings.
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
	 * Deletes all mocap recording files belonging to any recording player.
	 * Scans the recordings directory for files matching the {@code <name>_loop*} pattern.
	 */
	public static void removeAllRecordings() {
		Path recordingsDir = worldFolder.resolve("mocap_files").resolve("recordings");
		if (!Files.isDirectory(recordingsDir)) return;

		Set<String> playerPrefixes = new java.util.HashSet<>();
		loopSceneManager.forEachRecordingPlayer(pd ->
				playerPrefixes.add(pd.getName().toLowerCase() + "_loop"));

		try (var stream = Files.newDirectoryStream(recordingsDir, "*.mcmocap_rec")) {
			for (Path file : stream) {
				String fileName = file.getFileName().toString();
				String stem = fileName.substring(0, fileName.length() - ".mcmocap_rec".length());
				for (String prefix : playerPrefixes) {
					if (stem.startsWith(prefix)) {
						try {
							Files.delete(file);
							LOOP_LOGGER.info("Deleted recording file: {}", file);
						} catch (IOException e) {
							LOOP_LOGGER.error("Failed to delete recording file: {}", file);
						}
						break;
					}
				}
			}
		} catch (IOException e) {
			LOOP_LOGGER.error("Failed to scan recordings directory for cleanup", e);
		}
	}

	/**
	 * Removes outdated entries from the scene file to ensure the number of subscenes does not exceed the maximum allowed loops.
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
						if (elemLoop >= 0) {
							playerData.removeAudio(elemLoop);
							AudioPersistence.deleteIterationAudio(playerData.getName(), elemLoop);
						}
					}
				} catch (NumberFormatException e) {
					toKeep.add(element);
				}
			}

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

	public static CompoundTag saveFullInventory(Player player, HolderLookup.Provider provider) {
		CompoundTag tag = new CompoundTag();

		// Main inventory
		ListTag main = new ListTag();
		for (ItemStack stack : player.getInventory().items) {
			main.add(stack.isEmpty() ? new CompoundTag() : stack.save(provider, new CompoundTag()));
		}
		tag.put("main", main);

		// Armor
		ListTag armor = new ListTag();
		for (ItemStack stack : player.getInventory().armor) {
			armor.add(stack.isEmpty() ? new CompoundTag() : stack.save(provider, new CompoundTag()));
		}
		tag.put("armor", armor);

		// Offhand
		ListTag offhand = new ListTag();
		for (ItemStack stack : player.getInventory().offhand) {
			offhand.add(stack.isEmpty() ? new CompoundTag() : stack.save(provider, new CompoundTag()));
		}
		tag.put("offhand", offhand);

		return tag;
	}

	public static void loadFullInventory(Player player, CompoundTag tag, HolderLookup.Provider provider) {
		// Main inventory
		ListTag main = tag.getList("main", 10);
		for (int i = 0; i < player.getInventory().items.size(); i++) {
			CompoundTag itemTag = i < main.size() ? main.getCompound(i) : new CompoundTag();
			player.getInventory().items.set(i, itemTag.isEmpty() ? ItemStack.EMPTY : ItemStack.parseOptional(provider, itemTag));
		}

		// Armor
		ListTag armor = tag.getList("armor", 10);
		for (int i = 0; i < player.getInventory().armor.size(); i++) {
			CompoundTag itemTag = i < armor.size() ? armor.getCompound(i) : new CompoundTag();
			player.getInventory().armor.set(i, itemTag.isEmpty() ? ItemStack.EMPTY : ItemStack.parseOptional(provider, itemTag));
		}

		// Offhand
		ListTag offhand = tag.getList("offhand", 10);
		for (int i = 0; i < player.getInventory().offhand.size(); i++) {
			CompoundTag itemTag = i < offhand.size() ? offhand.getCompound(i) : new CompoundTag();
			player.getInventory().offhand.set(i, itemTag.isEmpty() ? ItemStack.EMPTY : ItemStack.parseOptional(provider, itemTag));
		}
	}
}