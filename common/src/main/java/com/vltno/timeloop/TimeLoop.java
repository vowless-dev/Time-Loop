package com.vltno.timeloop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DynamicOps;
import com.vltno.timeloop.types.LoopTypes;
import com.vltno.timeloop.types.MaxLoopsTypes;
import com.vltno.timeloop.types.RewindTypes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class TimeLoop {
	public static final Logger LOOP_LOGGER = LoggerFactory.getLogger("TimeLoop");
	public static MinecraftServer server;
	public static ServerLevel serverLevel;
	
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

	public static boolean showLoopInfo;
	public static boolean displayTimeInTicks;
	public static boolean trackItems;
	public static LoopTypes loopType;
	public static boolean trackChat;
	public static boolean hurtLoopedPlayers;
	public static RewindTypes rewindType;
	public static boolean trackInventory;

	// The configuration object loaded from disk
	public static TimeLoopConfig config;

	// The loop scene manager object
	public static LoopSceneManager loopSceneManager;

	// Get the world folder path for config/recording loading
	public static Path worldFolder;

	public static void init() {
		loopBossBar = new LoopBossBar();

		LOOP_LOGGER.info("Initializing TimeLoop mod (Common)");
	}

	/**
	 * Executes a minecraft chat command.
	 */
	public static void executeCommand(String command) {
		if (server == null) {
			LOOP_LOGGER.error("Attempted to execute command while server is null: {}", command);
		}
		
		server.execute(() -> {
			try {
				Commands commands = server.getCommands();

				CommandSourceStack commandSource = server.createCommandSourceStack();

				LOOP_LOGGER.info("Executing command: {}", command);

				commands.getDispatcher().execute(command, commandSource);

				LOOP_LOGGER.info("Command executed successfully: {}", command);
			} catch (CommandSyntaxException e) {
				LOOP_LOGGER.error("Failed to execute command '{}' due to syntax error: {}", command, e.getMessage());
			} catch (Exception e) {
				LOOP_LOGGER.error("An unexpected error occurred while executing command '{}': {}", command, e.getMessage(), e);
			}
		});
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

        playerData.addTempOffset(convertTicksToSeconds(tickCounter));

        LOOP_LOGGER.info("Player {} changed dimension to {}. New active recording index is now {}. Temp offsets are {}",
                player.getName().getString(), currentDimension.location(), playerData.getActiveRecordingIndex(), playerData.getTempOffsets());

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
		saveRecordings();
		removeOldSceneEntries();
		executeCommand("mocap playback stop_all including_others");
		startRecordings();
		if (trackTimeOfDay) { serverLevel.setDayTime(startTimeOfDay); }

		loopSceneManager.forEachRecordingPlayer(playerData -> {
            if (!playerData.getActive()) return;

			String playerName = playerData.getName();
			String playerNickname = playerData.getNickname();
			Skin playerSkin = playerData.getSkin();
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
                        Vec3 spawnPosition = server.overworld().getSharedSpawnPos().getBottomCenter();
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

			String playerSceneName = loopSceneManager.getPlayerSceneName(playerName);

            String skin_from = "skin_from_player";

            switch (playerSkin.skinType) {
                case MINESKIN -> skin_from = "skin_from_mineskin";
                case FILE -> skin_from = "skin_from_file";
            }

			executeCommand(String.format("mocap playback start .%s '%s' %s %s", playerSceneName, playerNickname, skin_from, playerSkin.value));
		});

		loopIteration++;
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
		LOOP_LOGGER.info("Starting Loop");
		startRecordings();
	}

	/**
	 * Starts the recordings.
	 */
	public static void startRecordings() {
		// Start recording for every player
		loopSceneManager.forEachRecordingPlayer(playerData -> {
            if (!playerData.getActive()) return;

			String playerName = playerData.getName();
            playerData.resetActiveRecordingIndex();
            playerData.resetTempOffsets();
			executeCommand(String.format("mocap recording start %s", playerName));
		});
	}

	/**
	 * Saves the recordings.
	 */
	public static void saveRecordings() {
        // Stop and save recordings for each player
        loopSceneManager.forEachRecordingPlayer(playerData -> {
            if (!playerData.getActive()) return;

            String playerName = playerData.getName();
            String playerSceneName = loopSceneManager.getPlayerSceneName(playerName);

            int totalSegments = playerData.getActiveRecordingIndex();
            int lastSegmentIndex = totalSegments;

            // Iterate and save ALL active recording segments based on the index tracked in PlayerData.
            for (int i = 1; i <= totalSegments; i++) {
                String recordingToProcess = String.format("-+mc.%s.%d", playerName, i);
                String recordingName = String.format("%s_loop%d_idx%d", playerName.toLowerCase(), loopIteration, i);

                LOOP_LOGGER.info("Processing active recording segment {} for player: {}", i, playerName);

                // If this is the currently recording segment, we must first STOP it.
                // If it's an earlier segment, it's already in the 'waiting for decision' state
                // (due to the dimension split), so a second 'stop' would DISCARD it.
                if (i == lastSegmentIndex) {
                    // Manually stop the currently active recording (e.g., -+mc.Dev.2 or -+mc.Dev.1 if no split)
                    // This puts it into the 'waiting for decision' state.
                    LOOP_LOGGER.info("Stopping final segment: {}", recordingToProcess);
                    executeCommand(String.format("mocap recording stop %s", recordingToProcess));
                } else {
                    // If it's not the last segment, it's already in 'waiting for decision' from the split.
                    LOOP_LOGGER.info("Segment {} assumed to be in 'waiting' state from dimension split.", i);
                }

                // Now, run the SAVE command on the segment that is in the 'waiting for decision' state.
                executeCommand(String.format("mocap recording save %s %s", recordingName.toLowerCase(), recordingToProcess));

                executeCommand(String.format("mocap scenes add_to .%s %s", playerSceneName, recordingName.toLowerCase()));

                playerData.incrementActiveSubsceneIndex();

                LOOP_LOGGER.info("Tick counter: " + tickCounter);
                LOOP_LOGGER.info("Ticks left: " + ticksLeft);
                LOOP_LOGGER.info("i: " + i);
                LOOP_LOGGER.info("Get temp offset (i - 1): " + playerData.getTempOffset(i - 1));
                String subsceneName = String.format("%03d-%s", playerData.getActiveSubsceneIndex(), recordingName);
                LOOP_LOGGER.info("SUBSCENE NAME: " + subsceneName);
                executeCommand(String.format("mocap scenes modify .%s %s time start_delay %s", playerSceneName, subsceneName, playerData.getTempOffset(i - 1)));
            }
        });
	}

	/**
	 * Stops the loop.
	 */
	public static void stopLoop() {
		if (isLooping) {
			LOOP_LOGGER.info("Stopping loop");

			isLooping = false;
            config.isLooping = false;

            tickCounter = 0;
            config.tickCounter = 0;

            ticksLeft = loopLengthTicks;
            config.ticksLeft = loopLengthTicks;

			loopBossBar.visible(false);
			saveRecordings();
			loopSceneManager.saveRecordingPlayers();
			executeCommand("mocap playback stop_all including_others");
			LOOP_LOGGER.info("Loop stopped!");
		}
	}

	public static void modifyPlayerAttributes(String targetPlayerName, String newPlayerNickname, Skin newSkin) {
        PlayerData targetPlayer = loopSceneManager.getRecordingPlayer(targetPlayerName);

        targetPlayer.setNickname(newPlayerNickname);
        targetPlayer.setSkin(newSkin);
        LOOP_LOGGER.info("Modified loop attributes for player '{}' -> '{}' with skin '{}'", targetPlayerName, newPlayerNickname, newSkin.value);
	}

	/**
	 * Updates the entities to be tracked for recording and playback settings.
	 * This method modifies the entities being tracked based on the specified parameter,
	 * enabling or disabling item tracking.
	 *
	 * @param items A boolean value. If true, includes items in the tracking list.
	 *              If false, excludes items and tracks only vehicles.
	 */
	public static void updateEntitiesToTrack(boolean items) {
		String entitiesToTrack = "@vehicles" + (items ? ";@items" : "");
		executeCommand(String.format("mocap settings recording track_entities %s", entitiesToTrack));
		executeCommand(String.format("mocap settings playback play_entities %s", entitiesToTrack));
	}

	public static void updateInfoBar(int time, int timeLeft) {
		if (showLoopInfo && isLooping) {
			if (displayTimeInTicks) { loopBossBar.setBossBarName("Time Left: " + timeLeft); }
			else {loopBossBar.setBossBarName("Time Left: " + convertTicksToTime(timeLeft));}

			loopBossBar.setBossBarPercentage(time, timeLeft);
		}
	}

    public static void removeRecording(String recording) {
        try {
            Path recordingPath = worldFolder.resolve("mocap_files").resolve("recordings").resolve(recording + ".mcmocap_rec");
            Files.delete(recordingPath);
            LOOP_LOGGER.info("Deleted recording at {}", recordingPath);
        } catch (IOException e) {
            LOOP_LOGGER.error("Error deleting recording {}", recording);
        }
    }

	/**
	 * Removes outdated entries from the scene file to ensure the number of subscenes does not exceed the maximum allowed loops.
	 * The method checks if there are more recorded subscenes in the scene file than the value specified by maxLoops. If so,
	 * it removes the oldest entries to maintain the desired number. The updated data is then saved back to the file.
	 */
	private static void removeOldSceneEntries() {
		if (maxLoops >= 1 && (maxLoopsType == MaxLoopsTypes.KEEP_NEWEST || maxLoopsType == MaxLoopsTypes.KEEP_NEWEST_DELETE) && (loopIteration + 1) > maxLoops) {
			Path scenesDir = worldFolder.resolve("mocap_files").resolve("scenes");

			List<Path> sceneFiles = new ArrayList<>();
			loopSceneManager.forEachRecordingPlayer(playerData -> {
				String playerSceneName = loopSceneManager.getPlayerSceneName(playerData.getName());
				if (playerSceneName != null && !playerSceneName.isBlank()) {
					sceneFiles.add(scenesDir.resolve(playerSceneName + ".mcmocap_scene"));
				} else {
					LOOP_LOGGER.warn("Invalid playerSceneName encountered: {}", playerSceneName);
				}
			});

			if (sceneFiles.isEmpty()) {
				LOOP_LOGGER.warn("No scene files found to process.");
			}

			for (Path sceneFile : sceneFiles) {
				if (sceneFile.toFile().exists()) {
					try {
						String jsonContent = new String(Files.readAllBytes(sceneFile));
						JsonParser parser = new JsonParser();
						JsonObject jsonObject = parser.parse(jsonContent).getAsJsonObject();
						JsonArray subScenes = jsonObject.getAsJsonArray("subscenes");

						if (subScenes.size() > maxLoops) {
							int entriesToRemove = loopIteration - maxLoops;
							JsonArray newSubScenes = new JsonArray();
                            for (int subsceneIndex = subScenes.size() - 1; subsceneIndex >= 0; subsceneIndex--) {
                                JsonElement subScene = subScenes.get(subsceneIndex);
                                String subSceneName = subScene.getAsJsonObject().get("name").getAsString();
                                String[] subSceneNameSplit = subSceneName.split("_");

                                PlayerData playerData = loopSceneManager.getRecordingPlayer(subSceneNameSplit[0]);
                                if (playerData != null) {
                                    playerData.setActiveSubsceneIndex(maxLoops);

                                    int _loopIteration = Integer.parseInt(subSceneNameSplit[subSceneNameSplit.length - 2].split("loop")[1]);

                                    if (_loopIteration > entriesToRemove) {
                                        newSubScenes.add(subScene);
                                    } else if (maxLoopsType == MaxLoopsTypes.KEEP_NEWEST_DELETE){
                                        removeRecording(subSceneName);
                                    }
                                }
                            }
                            jsonObject.remove("subscenes");
							jsonObject.add("subscenes", newSubScenes);
							Files.write(sceneFile, jsonObject.toString().getBytes());
							LOOP_LOGGER.info("Removed old scene entries for file: {}", sceneFile);
						}
					} catch (IOException e) {
						LOOP_LOGGER.error("Failed to process scene file: {}", sceneFile, e);
					}
				} else {
					LOOP_LOGGER.error("Scene file does not exist: {}", sceneFile);
				}
			}
		}
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