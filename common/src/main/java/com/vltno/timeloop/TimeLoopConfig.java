package com.vltno.timeloop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vltno.timeloop.types.LoopTypes;
import com.vltno.timeloop.types.MaxLoopsTypes;
import com.vltno.timeloop.types.RewindTypes;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class TimeLoopConfig {

    /* ───────────── Loop state ───────────── */

    public String scenePrefix = "loop_scene";
    public boolean firstStart = true;
    public int loopIteration = 0;
    public boolean isLooping = false;
    public int loopLengthTicks = 6000; // Default: 6000 ticks (i.e. 5 minutes)
    public int maxLoops = 0;
    public MaxLoopsTypes maxLoopsType = MaxLoopsTypes.DISABLED;
    public long timeSetting = 13000;
    public long startTimeOfDay = 0;
    public boolean trackTimeOfDay = false;
    public int tickCounter = 0;
    public int ticksLeft = loopLengthTicks;

    /* ───────────── Settings ───────────── */

    public boolean showLoopInfo = true;
    public boolean displayTimeInTicks = false;
    public boolean trackItems = true;
    public int entityTrackingDistance = 32; // Distance in blocks for mocap entity tracking (items, vehicles)
    public LoopTypes loopType = LoopTypes.DURATION;
    public boolean trackChat = true;
    public boolean hurtLoopedPlayers = false;
    public RewindTypes rewindType = RewindTypes.NONE;
    public boolean trackInventory = false;

    /* ───────────── Voice interaction (sculk / warden) ───────────── */

    public boolean voiceInteractionEnabled = true;
    public int voiceInteractionThresholdDb = -50; // dB; range -127 to 0
    public int voiceInteractionCooldownTicks = 20; // 1 second between game events per entity
    public float voiceAudioDistance = 48.0f; // SVC audio channel distance in blocks
    public boolean showPlayerVoiceIcon = true; // Show SVC speaker icon above looped player entities
    public boolean trackVoice = true; // Record voice chat audio during the loop

    /* ───────────── Player data ───────────── */

    public Map<String, PlayerData> recordingPlayers = new HashMap<>();

    /* ───────────── Serialization (not persisted) ───────────── */

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;

    /**
     * Loads the configuration from the world directory.
     * If the file does not exist, a default config is created and saved.
     *
     * @param configDir the world root directory
     * @return an instance of TimeLoopConfig
     */
    public static TimeLoopConfig load(Path configDir) {
        configPath = configDir.resolve("timeloop.json");
        TimeLoopConfig config = null;

        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath)) {
                config = GSON.fromJson(reader, TimeLoopConfig.class);
            } catch (Exception e) {
                TimeLoop.LOOP_LOGGER.error("Failed to load config file", e);
            }
        }

        if (config == null) {
            config = new TimeLoopConfig();
            TimeLoop.LOOP_LOGGER.info("Config file not found or invalid. Using default config.");
        }

        // Ensure recordingPlayers is never null (e.g. if JSON had "recordingPlayers": null)
        if (config.recordingPlayers == null) {
            config.recordingPlayers = new HashMap<>();
        }

        if (config.loopType == null) {
            TimeLoop.LOOP_LOGGER.warn("loopType was null (possibly an old config using TICKS) — resetting to TIME");
            config.loopType = LoopTypes.DURATION;
        }

        // Initialize transient fields on every PlayerData that Gson deserialized.
        // Gson bypasses constructors, so transient fields (audio maps, activeSegment,
        // tempOffsets, etc.) would be null/0 without this.
        config.recordingPlayers.values().forEach(PlayerData::initTransients);

        // Validate numeric fields that a user may have hand-edited
        if (config.loopLengthTicks <= 0) {
            TimeLoop.LOOP_LOGGER.warn("loopLengthTicks was {} — resetting to default 6000", config.loopLengthTicks);
            config.loopLengthTicks = 6000;
        }
        if (config.maxLoops < 0) {
            config.maxLoops = 0;
        }
        if (config.entityTrackingDistance < 1) {
            TimeLoop.LOOP_LOGGER.warn("entityTrackingDistance was {} — resetting to default 32", config.entityTrackingDistance);
            config.entityTrackingDistance = 32;
        }
        if (config.voiceInteractionThresholdDb < -127 || config.voiceInteractionThresholdDb > 0) {
            TimeLoop.LOOP_LOGGER.warn("voiceInteractionThresholdDb was {} — resetting to default -50", config.voiceInteractionThresholdDb);
            config.voiceInteractionThresholdDb = -50;
        }
        if (config.voiceInteractionCooldownTicks < 1) {
            TimeLoop.LOOP_LOGGER.warn("voiceInteractionCooldownTicks was {} — resetting to default 20", config.voiceInteractionCooldownTicks);
            config.voiceInteractionCooldownTicks = 20;
        }
        if (config.voiceAudioDistance < 1.0f) {
            TimeLoop.LOOP_LOGGER.warn("voiceAudioDistance was {} — resetting to default 48", config.voiceAudioDistance);
            config.voiceAudioDistance = 48.0f;
        }

        config.save();
        return config;
    }

    /**
     * Saves the current configuration to disk.
     */
    public void save() {
        if (configPath == null) {
            TimeLoop.LOOP_LOGGER.error("Cannot save config: configPath is null (load() not called yet)");
            return;
        }
        try (Writer writer = Files.newBufferedWriter(configPath)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            TimeLoop.LOOP_LOGGER.error("Failed to save config file", e);
        }
    }
}