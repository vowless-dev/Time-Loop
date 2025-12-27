package com.vltno.timeloop;

import com.vltno.timeloop.voicechat.TimedAudioFrame;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlayerData {
    private final String name;
    private String nickname;
    private Skin skin;
    private Vec3 startPosition;
    private Vec3 joinPosition;
    private String inventoryTag;
    private ResourceKey<Level> lastDimensionKey;
    private int activeRecordingIndex;
    private int activeSubsceneIndex;
    private ArrayList<Float> tempOffsets;
    private boolean active;

    private Map<Integer, Map<Integer, List<byte[]>>> voiceData = new ConcurrentHashMap<>();

    private final Map<Integer, Map<Integer, List<Vec3>>> positionData = new ConcurrentHashMap<>(); // TODO: remove this if mocap api exposes position of playback

    private final Map<Integer, Map<Integer, List<TimedAudioFrame>>> timedVoiceData = new ConcurrentHashMap<>();

    private int totalFramesRecorded = 0;

    public PlayerData(String name, String nickname, String skin, Vec3 joinPosition, CompoundTag inventoryTag) {
        this.name = name;
        this.nickname = nickname;
        this.skin = new Skin();
        this.skin.value = name;
        this.startPosition = null;
        this.joinPosition = joinPosition;
        this.setInventoryTag(inventoryTag);
        this.lastDimensionKey = null;
        this.activeRecordingIndex = 1;
        this.activeSubsceneIndex = 0;
        this.tempOffsets = new ArrayList<Float>();
        this.tempOffsets.add(0f);
        this.active = true;
        this.totalFramesRecorded = 0;
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
        return startPosition;
    }

    public void setStartPosition(Vec3 newStartPosition) {
        this.startPosition = newStartPosition;
    }

    public Vec3 getJoinPosition() {
        return joinPosition;
    }
    
    public void setJoinPosition(Vec3 newJoinPosition) {
        this.joinPosition = newJoinPosition;
    }

    public void setInventoryTag(CompoundTag inventoryTag) {
        this.inventoryTag = inventoryTag.toString();
    }

    public CompoundTag getInventoryTag() {
        if (inventoryTag == null || inventoryTag.isEmpty()) return new CompoundTag();
        try {
            Tag tag = TagParser.parseCompoundFully(this.inventoryTag);
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

    public int getActiveRecordingIndex() {
        return activeRecordingIndex;
    }

    public void incrementActiveRecordingIndex() {
        this.activeRecordingIndex++;
    }

    public void resetActiveRecordingIndex() {
        this.activeRecordingIndex = 1;
    }

    public int getActiveSubsceneIndex() {
        return activeSubsceneIndex;
    }

    public void incrementActiveSubsceneIndex() {
        this.activeSubsceneIndex++;
    }

    public void setActiveSubsceneIndex(int subsceneIndex) {
        this.activeSubsceneIndex = subsceneIndex;
    }

    public void resetActiveSubsceneIndex() {
        this.activeSubsceneIndex = 1;
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

    public void addAudioFrame(byte[] data) {
        int currentTickOfLoop = TimeLoop.tickCounter;
        int currentIteration = TimeLoop.loopIteration;
        int segmentIndex = this.activeRecordingIndex;

        timedVoiceData.computeIfAbsent(currentIteration, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(segmentIndex, k -> new CopyOnWriteArrayList<>())
                .add(new TimedAudioFrame(currentTickOfLoop, totalFramesRecorded++, data));
    }

    public Map<Integer, List<TimedAudioFrame>> getTimedVoiceDataForIteration(int iteration) {
        return timedVoiceData.getOrDefault(iteration, java.util.Collections.emptyMap());
    }

    public List<byte[]> getAudioFrames(int iteration, int index) {
        Map<Integer, List<byte[]>> iterData = voiceData.get(iteration);
        if (iterData != null) {
            List<byte[]> frames = iterData.get(index);
            if (frames != null) return frames;
        }
        return Collections.emptyList();
    }

    public Map<Integer, List<byte[]>> getAllAudioSegmentsForIteration(int iteration) {
        Map<Integer, Map<Integer, List<byte[]>>> voiceMap = this.voiceData;
        if (voiceMap.containsKey(iteration)) {
            return voiceMap.get(iteration);
        }
        return Collections.emptyMap();
    }

    public void addPositionFrame(Vec3 pos) {
        positionData.computeIfAbsent(TimeLoop.loopIteration, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(this.activeRecordingIndex, k -> new CopyOnWriteArrayList<>())
                .add(pos);
    }

    public Vec3 getPositionAtFrame(int iteration, int index, int frameIndex) {
        if (positionData.containsKey(iteration) && positionData.get(iteration).containsKey(index)) {
            List<Vec3> positions = positionData.get(iteration).get(index);
            if (frameIndex < positions.size()) {
                return positions.get(frameIndex);
            }
            // Fallback to last known position if audio outlasts position recording
            return positions.get(positions.size() - 1);
        }
        return null;
    }

    public void resetTotalFramesRecorded() {
        this.totalFramesRecorded = 0;
    }
}
