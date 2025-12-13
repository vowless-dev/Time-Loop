package com.vltno.timeloop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DynamicOps;
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

        // Log message for debugging dimension change
        LOOP_LOGGER.info("Player {} changed dimension to {}.",
                player.getName().getString(), player.level().dimension().location().toString());

        PlayerData playerData = loopSceneManager.getRecordingPlayer(player.getGameProfile().name());

        if (playerData == null) {
            LOOP_LOGGER.warn("Player {} changed dimensions but is not tracked for the loop.", player.getName().getString());
            return;
        }

        ResourceKey<Level> currentDimension = player.level().dimension();
        ResourceKey<Level> lastDimension = playerData.getLastDimensionKey();

        // Check if dimension has actually changed since the last update
        if (currentDimension.equals(lastDimension) && lastDimension != null) {
            return;
        }

        playerData.incrementActiveRecordingIndex();

        LOOP_LOGGER.info("Player {} changed dimension to {}. New active recording index is now {}.",
                player.getName().getString(), currentDimension.location(), playerData.getActiveRecordingIndex());

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
        if (loopIteration + 1 >= maxLoops && maxLoops != 0) {
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
			String playerName = playerData.getName();
			String playerNickname = playerData.getNickname();
			String playerSkin = playerData.getSkin();
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

                double x = 0.0, y = 0.0, z = 0.0;
                switch (rewindType) {
                    case START_POSITION -> {
                        if (startPosition == null) {
                            LOOP_LOGGER.error("Player {} has no start position yet. Defaulting to join position.", playerName);
                            x = joinPosition.x;
                            y = joinPosition.y;
                            z = joinPosition.z;
                        } else {
                            x = startPosition.x;
                            y = startPosition.y;
                            z = startPosition.z;
                        }
                    }
                    case JOIN_POSITION -> {
                        if (joinPosition == null) {
                            LOOP_LOGGER.error("Player {} has no join position yet. (somehow??)", playerName);
                            return;
                        }
                        x = joinPosition.x;
                        y = joinPosition.y;
                        z = joinPosition.z;
                    }
                    case SPAWN_POSITION -> {
                        BlockPos spawnPosition = server.overworld().getRespawnData().pos();
                        if (spawnPosition == null) {
                            LOOP_LOGGER.error("Server has no spawn position yet. (somehow??)");
                            return;
                        }
                        x = spawnPosition.getX();
                        y = spawnPosition.getY();
                        z = spawnPosition.getZ();
                    }
                }
                player.teleportTo(targetLevel, x, y, z, absoluteMovement, yaw, pitch, setCamera);
            }
            playerData.setLastDimensionKey(player.level().dimension());

			String playerSceneName = loopSceneManager.getPlayerSceneName(playerName);
			TimeLoop.executeCommand(String.format("mocap playback start .%s %s skin_from_player %s", playerSceneName, playerNickname, playerSkin));
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
			String playerName = playerData.getName();
            playerData.resetActiveRecordingIndex();
			executeCommand(String.format("mocap recording start %s", playerName));
		});
	}

	/**
	 * Saves the recordings.
	 */
	public static void saveRecordings() {
        // Stop and save recordings for each player
        loopSceneManager.forEachRecordingPlayer(playerData -> {
            String playerName = playerData.getName();
            String playerSceneName = loopSceneManager.getPlayerSceneName(playerName);

            int totalSegments = playerData.getActiveRecordingIndex();
            int lastSegmentIndex = totalSegments;

            // Iterate and save ALL active recording segments based on the index tracked in PlayerData.
            for (int i = 1; i <= totalSegments; i++) {
                String recordingToProcess = String.format("-+mc.%s.%d", playerName, i);
                String recordingName = String.format("%s_loop%d_idx%d", playerName, loopIteration, i);

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

                // Add the segment to the scene.
                executeCommand(String.format("mocap scenes add_to .%s %s", playerSceneName, recordingName.toLowerCase()));
            }
        });
	}

	/**
	 * Stops the loop.
	 */
	public static void stopLoop(boolean tempStop) {
		if (isLooping) {
			LOOP_LOGGER.info("Stopping loop");
			isLooping = false;
            if (!tempStop) {
                config.isLooping = false;
                tickCounter = 0;
                ticksLeft = loopLengthTicks;
            }
			loopBossBar.visible(false);
			saveRecordings();
			loopSceneManager.saveRecordingPlayers();
			executeCommand("mocap playback stop_all including_others");
			LOOP_LOGGER.info("Loop stopped!");
		}
	}

    public static void stopLoop() {
        stopLoop(false);
    }

	public static void modifyPlayerAttributes(String targetPlayerName, String newPlayerNickname, String newSkin) {
		String playerSceneName = loopSceneManager.getPlayerSceneName(targetPlayerName);
		executeCommand(String.format("mocap scenes modify .%s %s player_skin skin_from_player %s", playerSceneName, newPlayerNickname, newSkin));

		loopSceneManager.forEachRecordingPlayer(playerData -> {
			if (playerData.getName().equals(targetPlayerName)) {
				playerData.setNickname(newPlayerNickname);
				playerData.setSkin(newSkin);
				LOOP_LOGGER.info("Modified loop attributes for player '{}' -> '{}' with skin '{}'", targetPlayerName, newPlayerNickname, newSkin);
			}
		});
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

	/**
	 * Removes outdated entries from the scene file to ensure the number of subscenes does not exceed the maximum allowed loops.
	 * The method checks if there are more recorded subscenes in the scene file than the value specified by maxLoops. If so,
	 * it removes the oldest entries to maintain the desired number. The updated data is then saved back to the file.
	 *
	 */
	private static void removeOldSceneEntries() {
		if (isLooping && maxLoops > 1) {
			Path sceneDir = worldFolder.resolve("mocap_files").resolve("scenes");

			List<Path> sceneFiles = new ArrayList<>();
			loopSceneManager.forEachRecordingPlayer(playerData -> {
				String playerSceneName = loopSceneManager.getPlayerSceneName(playerData.getName());
				if (playerSceneName != null && !playerSceneName.isBlank()) {
					sceneFiles.add(sceneDir.resolve(playerSceneName + ".mcmocap_scene"));
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
							int entriesToRemove = subScenes.size() - maxLoops;
							JsonArray newSubScenes = new JsonArray();
							for (int i = entriesToRemove; i < subScenes.size(); i++) {
								newSubScenes.add(subScenes.get(i));
							}
							jsonObject.add("subScenes", newSubScenes);
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
	 *
	 * @param ticksLeft A int value.
	 */
	public static String convertTicksToTime(int ticksLeft) {
		int timeLeft = ticksLeft / 20;
		int hours = timeLeft / 3600;
		int minutes = (timeLeft % 3600) / 60;
		int seconds = timeLeft % 60;
		return String.format("%02d:%02d:%02d", hours, minutes, seconds);
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