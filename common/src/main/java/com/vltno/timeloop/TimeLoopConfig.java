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
    public int ticksLeft;

    public boolean showLoopInfo = true;
    public boolean displayTimeInTicks = false;
    public boolean trackItems = true;
    public LoopTypes loopType = LoopTypes.TICKS;
    public boolean trackChat = true;
    public boolean hurtLoopedPlayers = false;
    public RewindTypes rewindType = RewindTypes.NONE;
    public boolean trackInventory = false;

    public Map<String, PlayerData> recordingPlayers = new HashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;

    /**
     * Loads the configuration from the world directory.
     * If the file does not exist, a default config is created and saved.
     *
     * @param configDir the config directory (usually obtained from FabricLoader)
     * @return an instance of TimeLoopConfig
     */
    public static TimeLoopConfig load(Path configDir) {
        configPath = configDir.resolve("timeloop.json");
        TimeLoopConfig config = null;

        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath)) {
                config = GSON.fromJson(reader, TimeLoopConfig.class);
            } catch (Exception e) {
                TimeLoop.LOOP_LOGGER.error("Failed to load config file: " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (config == null) {
            config = new TimeLoopConfig();
            TimeLoop.LOOP_LOGGER.error("Config file not found or invalid. Using default config.");
        }

        // Validate recordingPlayers field and provide defaults if necessary
        if (!(config.recordingPlayers instanceof Map)) {
            TimeLoop.LOOP_LOGGER.error("Invalid or missing recordingPlayers data in config. Initializing with an empty map.");
            config.recordingPlayers = new HashMap<>();
        }

        config.save();
        return config;
    }

    /**
     * Saves the current configuration to disk.
     */
    public void save() {
        try (Writer writer = Files.newBufferedWriter(configPath)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}