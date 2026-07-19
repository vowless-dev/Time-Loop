package com.vltno.timeloop;

import com.vltno.timeloop.compat.voicechat.AudioPersistence;
import com.vltno.timeloop.compat.voicechat.TimedAudioFrame;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlayerData {
    // ── Persisted fields (serialized to config JSON via Gson) ──
    private final String name;
    private String nickname;
    private Skin skin;
    private String inventoryTag;
    private boolean active;

    // Vec3 is stored as doubles for Gson (Vec3 has no no-arg constructor)
    private double startPosX, startPosY, startPosZ;
    private boolean hasStartPosition;
    private double joinPosX, joinPosY, joinPosZ;

    // ── Runtime-only fields (excluded from Gson serialization) ─-
    private transient ResourceKey<Level> lastDimensionKey;
    private transient ResourceKey<Level> startDimensionKey;
    private transient int activeRecordingIndex;
    private transient ArrayList<Float> tempOffsets;
    private transient int activeSegment;

    public PlayerData(String name, String nickname, String skin, Vec3 joinPosition, CompoundTag inventoryTag) {
        this.name = name;
        this.nickname = nickname;
        this.skin = new Skin();
        this.skin.value = skin;
        this.hasStartPosition = false;
        setJoinPosition(joinPosition);
        this.setInventoryTag(inventoryTag);
        this.active = true;
        initTransients();
    }

    /**
     * Initialises transient fields that Gson skips during deserialization.
     * Must be called after Gson constructs an instance (e.g. in config load).
     */
    public void initTransients() {
        this.lastDimensionKey = null;
        this.startDimensionKey = null;
        this.activeRecordingIndex = 1;
        this.tempOffsets = new ArrayList<>();
        this.tempOffsets.add(0f);
        this.activeSegment = 1;
        // Audio maps are transient (saved separately by AudioPersistence)
        this.audio = new ConcurrentHashMap<>();
        this.positions = new ConcurrentHashMap<>();
    }

    public String getName() {
        return name;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Skin getSkin() {
        return skin;
    }

    public void setSkin(Skin skin) {
        this.skin = skin;
    }

    public Vec3 getStartPosition() {
        return hasStartPosition ? new Vec3(startPosX, startPosY, startPosZ) : null;
    }

    public void setStartPosition(Vec3 pos) {
        if (pos == null) {
            this.hasStartPosition = false;
        } else {
            this.startPosX = pos.x;
            this.startPosY = pos.y;
            this.startPosZ = pos.z;
            this.hasStartPosition = true;
        }
    }

    public Vec3 getJoinPosition() {
        return new Vec3(joinPosX, joinPosY, joinPosZ);
    }

    public void setJoinPosition(Vec3 pos) {
        this.joinPosX = pos.x;
        this.joinPosY = pos.y;
        this.joinPosZ = pos.z;
    }

    public void setInventoryTag(CompoundTag inventoryTag) {
        this.inventoryTag = inventoryTag.toString();
    }

    public CompoundTag getInventoryTag() {
        if (inventoryTag == null || inventoryTag.isEmpty()) return new CompoundTag();
        try {
            Tag tag = TagParser.parseTag(this.inventoryTag);
            if (tag instanceof CompoundTag compoundTag) {
                return compoundTag;
            }
        } catch (Exception e) {
            TimeLoop.LOOP_LOGGER.error("Failed to parse inventoryTagNbt: " + inventoryTag, e);
        }
        return new CompoundTag();
    }

    public ResourceKey<Level> getLastDimensionKey() {
        return lastDimensionKey;
    }

    public void setLastDimensionKey(ResourceKey<Level> lastDimensionKey) {
        this.lastDimensionKey = lastDimensionKey;
    }

    public ResourceKey<Level> getStartDimensionKey() {
        return startDimensionKey;
    }

    public void setStartDimensionKey(ResourceKey<Level> startDimensionKey) {
        this.startDimensionKey = startDimensionKey;
    }

    public int getActiveRecordingIndex() {
        return activeRecordingIndex;
    }

    public void incrementActiveRecordingIndex() {
        this.activeRecordingIndex++;
    }

    public void resetActiveRecordingIndex() {
        this.activeRecordingIndex = 1;
    }

    public void addTempOffset(float value) {
        this.tempOffsets.add(value);
    }

    public float getTempOffset(int index) {
        if (index == -1) {
            return tempOffsets.getLast();
        }
        return tempOffsets.get(index);
    }

    public void resetTempOffsets() {
        this.tempOffsets.clear();
        this.tempOffsets.add(0f);
    }

    public ArrayList<Float> getTempOffsets() {
        return tempOffsets;
    }

    public boolean getActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }


    /* ───────────────── SEGMENTS ───────────────── */

    public void resetSegments() {
        activeSegment = 0;
    }

    public void nextSegment() {
        activeSegment++;
    }

    public int getActiveSegment() {
        return activeSegment;
    }

    /* ───────────────── RECORDING (transient — saved via AudioPersistence) ───────────────── */

    // iter → seg → list of timed opus frames
    private transient Map<Integer, Map<Integer, List<TimedAudioFrame>>> audio = new ConcurrentHashMap<>();
    // iter → seg → tick → position (TreeMap for floor-lookup)
    private transient Map<Integer, Map<Integer, TreeMap<Integer, Vec3>>> positions = new ConcurrentHashMap<>();

    public void addVoiceFrame(int iteration, int tick, byte[] opus, Vec3 pos) {
        int seg = activeSegment;

        audio.computeIfAbsent(iteration, i -> new ConcurrentHashMap<>())
                .computeIfAbsent(seg, s -> new CopyOnWriteArrayList<>())
                .add(new TimedAudioFrame(tick, opus));

        positions.computeIfAbsent(iteration, i -> new ConcurrentHashMap<>())
                .computeIfAbsent(seg, s -> new TreeMap<>())
                .put(tick, pos);
    }

    /**
     * Adds a voice frame for a specific iteration and segment.
     * Used by {@link AudioPersistence} when loading
     * saved audio from disk (where the segment is known explicitly, not derived
     * from the active segment).
     */
    public void addVoiceFrameForLoad(int iteration, int segment, int tick, byte[] opus, Vec3 pos) {
        audio.computeIfAbsent(iteration, i -> new ConcurrentHashMap<>())
                .computeIfAbsent(segment, s -> new CopyOnWriteArrayList<>())
                .add(new TimedAudioFrame(tick, opus));

        positions.computeIfAbsent(iteration, i -> new ConcurrentHashMap<>())
                .computeIfAbsent(segment, s -> new TreeMap<>())
                .put(tick, pos);
    }

    public Map<Integer, List<TimedAudioFrame>> getAudio(int iteration) {
        return audio.getOrDefault(iteration, Map.of());
    }

    /**
     * Removes all in-memory audio and position data for the given iteration.
     * Called when old scene entries are trimmed to keep memory in sync with disk.
     */
    public void removeAudio(int iteration) {
        audio.remove(iteration);
        positions.remove(iteration);
    }

    /**
     * Removes all in-memory audio and position data for every iteration.
     * Called on loop reset to clear all voice data.
     */
    public void removeAllAudio() {
        audio.clear();
        positions.clear();
    }

    /**
     * Look up the player position for a given tick using floor-key lookup.
     * Returns the position at the latest tick <= the requested tick,
     * or null if no positions were recorded for this iteration/segment.
     */
    public Vec3 getPosition(int iteration, int segment, int tick) {
        var segMap = positions.get(iteration);
        if (segMap == null) return null;
        var tree = segMap.get(segment);
        if (tree == null || tree.isEmpty()) return null;
        var entry = tree.floorEntry(tick);
        return entry != null ? entry.getValue() : tree.firstEntry().getValue();
    }
}